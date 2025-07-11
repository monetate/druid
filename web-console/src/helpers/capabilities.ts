/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import type { DruidEngine } from '../druid-models';
import { Api } from '../singletons';
import { filterMap } from '../utils';

import { maybeGetClusterCapacity } from './index';

export type CapabilitiesMode = 'full' | 'no-sql' | 'no-proxy';

export type CapabilitiesModeExtended =
  | 'full'
  | 'no-sql'
  | 'no-proxy'
  | 'no-sql-no-proxy'
  | 'coordinator-overlord'
  | 'coordinator'
  | 'overlord';

export type QueryType = 'none' | 'nativeOnly' | 'nativeAndSql';

const FUNCTION_SQL = `SELECT "ROUTINE_NAME", "SIGNATURES", "IS_AGGREGATOR" FROM "INFORMATION_SCHEMA"."ROUTINES" WHERE "SIGNATURES" IS NOT NULL`;

type FunctionRow = [string, string, string];

export interface FunctionsDefinition {
  args: string[];
  isAggregate: boolean;
}

export type AvailableFunctions = Map<string, FunctionsDefinition>;

function functionRowsToMap(functionRows: FunctionRow[]): AvailableFunctions {
  return new Map<string, FunctionsDefinition>(
    filterMap(functionRows, ([ROUTINE_NAME, SIGNATURES, IS_AGGREGATOR]) => {
      if (!SIGNATURES) return;
      const args = filterMap(SIGNATURES.replace(/'/g, '').split('\n'), sig => {
        if (!sig.startsWith(`${ROUTINE_NAME}(`) || !sig.endsWith(')')) return;
        return sig.slice(ROUTINE_NAME.length + 1, sig.length - 1);
      });
      if (!args.length) return;
      return [
        ROUTINE_NAME,
        {
          args,
          isAggregate: IS_AGGREGATOR === 'YES',
        },
      ];
    }),
  );
}

export interface CapabilitiesValue {
  queryType: QueryType;
  multiStageQueryTask: boolean;
  multiStageQueryDart: boolean;
  coordinator: boolean;
  overlord: boolean;
  maxTaskSlots?: number;
  availableSqlFunctions?: AvailableFunctions;
}

export class Capabilities {
  static STATUS_TIMEOUT = 15000;
  static FULL: Capabilities;
  static NO_SQL: Capabilities;
  static COORDINATOR_OVERLORD: Capabilities;
  static COORDINATOR: Capabilities;
  static OVERLORD: Capabilities;
  static NO_PROXY: Capabilities;

  static async detectQueryType(): Promise<QueryType | undefined> {
    // Check SQL endpoint
    try {
      await Api.instance.post(
        '/druid/v2/sql?capabilities',
        { query: 'SELECT 1337', context: { timeout: Capabilities.STATUS_TIMEOUT } },
        { timeout: Capabilities.STATUS_TIMEOUT },
      );
    } catch (e) {
      const status = e.response?.status;
      if (status !== 405 && status !== 404) {
        return; // other failure
      }
      try {
        await Api.instance.get('/status?capabilities', { timeout: Capabilities.STATUS_TIMEOUT });
      } catch (e) {
        return; // total failure
      }
      // Status works but SQL 405s => the SQL endpoint is disabled

      try {
        await Api.instance.post(
          '/druid/v2?capabilities',
          {
            queryType: 'dataSourceMetadata',
            dataSource: '__web_console_probe__',
            context: { timeout: Capabilities.STATUS_TIMEOUT },
          },
          { timeout: Capabilities.STATUS_TIMEOUT },
        );
      } catch (e) {
        if (status !== 405 && status !== 404) {
          return; // other failure
        }

        return 'none';
      }

      return 'nativeOnly';
    }

    return 'nativeAndSql';
  }

  static async detectManagementProxy(): Promise<boolean> {
    try {
      await Api.instance.get(`/proxy/enabled?capabilities`, {
        timeout: Capabilities.STATUS_TIMEOUT,
      });
    } catch (e) {
      const status = e.response?.status;
      // If we detect error code 400 the management proxy is enabled but just does not know about the recently added /proxy/enabled route so treat this as a win.
      return status === 400;
    }

    return true;
  }

  static async detectNode(node: 'coordinator' | 'overlord'): Promise<boolean> {
    try {
      await Api.instance.get(
        `/druid/${node === 'overlord' ? 'indexer' : node}/v1/isLeader?capabilities`,
        {
          timeout: Capabilities.STATUS_TIMEOUT,
        },
      );
    } catch (e) {
      return false;
    }

    return true;
  }

  static async detectMultiStageQueryTask(): Promise<boolean> {
    try {
      const resp = await Api.instance.get(`/druid/v2/sql/task/enabled?capabilities`);
      return Boolean(resp.data.enabled);
    } catch {
      return false;
    }
  }

  static async detectMultiStageQueryDart(): Promise<boolean> {
    try {
      const resp = (await Api.instance.get(`/druid/v2/sql/engines?capabilities`)).data;
      return (
        Array.isArray(resp.engines) &&
        resp.engines.some(({ name }: { name: string }) => name === 'msq-dart')
      );
    } catch {
      return false;
    }
  }

  static async detectCapabilities(): Promise<Capabilities | undefined> {
    const queryType = await Capabilities.detectQueryType();
    if (typeof queryType === 'undefined') return;

    let coordinator: boolean;
    let overlord: boolean;
    if (queryType === 'none') {
      // must not be running on the router, figure out what node the console is on (or both?)
      coordinator = await Capabilities.detectNode('coordinator');
      overlord = await Capabilities.detectNode('overlord');
    } else {
      // must be running on the router, figure out if the management proxy is working
      coordinator = overlord = await Capabilities.detectManagementProxy();
    }

    const [multiStageQueryTask, multiStageQueryDart] = await Promise.all([
      Capabilities.detectMultiStageQueryTask(),
      Capabilities.detectMultiStageQueryDart(),
    ]);

    return new Capabilities({
      queryType,
      multiStageQueryTask,
      multiStageQueryDart,
      coordinator,
      overlord,
    });
  }

  static async detectCapacity(capabilities: Capabilities): Promise<Capabilities> {
    if (!capabilities.hasOverlordAccess()) return capabilities;

    const capacity = await maybeGetClusterCapacity();
    if (!capacity) return capabilities;

    return new Capabilities({
      ...capabilities.valueOf(),
      maxTaskSlots: capacity.totalTaskSlots,
    });
  }

  static async detectAvailableSqlFunctions(
    capabilities: Capabilities,
  ): Promise<AvailableFunctions | undefined> {
    if (!capabilities.hasSql()) return;

    try {
      return functionRowsToMap(
        (
          await Api.instance.post<FunctionRow[]>(
            '/druid/v2/sql?capabilities-functions',
            {
              query: FUNCTION_SQL,
              resultFormat: 'array',
              context: { timeout: Capabilities.STATUS_TIMEOUT },
            },
            { timeout: Capabilities.STATUS_TIMEOUT },
          )
        ).data,
      );
    } catch (e) {
      console.error(e);
      return;
    }
  }

  private readonly queryType: QueryType;
  private readonly multiStageQueryTask: boolean;
  private readonly multiStageQueryDart: boolean;
  private readonly coordinator: boolean;
  private readonly overlord: boolean;
  private readonly maxTaskSlots?: number;

  constructor(value: CapabilitiesValue) {
    this.queryType = value.queryType;
    this.multiStageQueryTask = value.multiStageQueryTask;
    this.multiStageQueryDart = value.multiStageQueryDart;
    this.coordinator = value.coordinator;
    this.overlord = value.overlord;
    this.maxTaskSlots = value.maxTaskSlots;
  }

  public valueOf(): CapabilitiesValue {
    return {
      queryType: this.queryType,
      multiStageQueryTask: this.multiStageQueryTask,
      multiStageQueryDart: this.multiStageQueryDart,
      coordinator: this.coordinator,
      overlord: this.overlord,
      maxTaskSlots: this.maxTaskSlots,
    };
  }

  public clone(): Capabilities {
    return new Capabilities(this.valueOf());
  }

  public getMode(): CapabilitiesMode {
    if (!this.hasSql()) return 'no-sql';
    if (!this.hasCoordinatorAccess()) return 'no-proxy';
    return 'full';
  }

  public getModeExtended(): CapabilitiesModeExtended | undefined {
    const { queryType, coordinator, overlord } = this;

    if (queryType === 'nativeAndSql') {
      if (coordinator && overlord) {
        return 'full';
      }
      if (!coordinator && !overlord) {
        return 'no-proxy';
      }
    } else if (queryType === 'nativeOnly') {
      if (coordinator && overlord) {
        return 'no-sql';
      }
      if (!coordinator && !overlord) {
        return 'no-sql-no-proxy';
      }
    } else {
      if (coordinator && overlord) {
        return 'coordinator-overlord';
      }
      if (coordinator) {
        return 'coordinator';
      }
      if (overlord) {
        return 'overlord';
      }
    }

    return;
  }

  public hasEverything(): boolean {
    return this.queryType === 'nativeAndSql' && this.coordinator && this.overlord;
  }

  public hasQuerying(): boolean {
    return this.queryType !== 'none';
  }

  public hasSql(): boolean {
    return this.queryType === 'nativeAndSql';
  }

  public hasMultiStageQueryTask(): boolean {
    return this.multiStageQueryTask;
  }

  public hasMultiStageQueryDart(): boolean {
    return this.multiStageQueryDart;
  }

  public getSupportedQueryEngines(): DruidEngine[] {
    const queryEngines: DruidEngine[] = ['native'];
    if (this.hasSql()) {
      queryEngines.push('sql-native');
    }
    if (this.hasMultiStageQueryTask()) {
      queryEngines.push('sql-msq-task');
    }
    if (this.hasMultiStageQueryDart()) {
      queryEngines.push('sql-msq-dart');
    }
    return queryEngines;
  }

  public hasCoordinatorAccess(): boolean {
    return this.coordinator;
  }

  public hasSqlOrCoordinatorAccess(): boolean {
    return this.hasSql() || this.hasCoordinatorAccess();
  }

  public hasOverlordAccess(): boolean {
    return this.overlord;
  }

  public hasSqlOrOverlordAccess(): boolean {
    return this.hasSql() || this.hasOverlordAccess();
  }

  public getMaxTaskSlots(): number | undefined {
    return this.maxTaskSlots;
  }
}
Capabilities.FULL = new Capabilities({
  queryType: 'nativeAndSql',
  multiStageQueryTask: true,
  multiStageQueryDart: true,
  coordinator: true,
  overlord: true,
});
Capabilities.NO_SQL = new Capabilities({
  queryType: 'nativeOnly',
  multiStageQueryTask: false,
  multiStageQueryDart: false,
  coordinator: true,
  overlord: true,
});
Capabilities.COORDINATOR_OVERLORD = new Capabilities({
  queryType: 'none',
  multiStageQueryTask: false,
  multiStageQueryDart: false,
  coordinator: true,
  overlord: true,
});
Capabilities.COORDINATOR = new Capabilities({
  queryType: 'none',
  multiStageQueryTask: false,
  multiStageQueryDart: false,
  coordinator: true,
  overlord: false,
});
Capabilities.OVERLORD = new Capabilities({
  queryType: 'none',
  multiStageQueryTask: false,
  multiStageQueryDart: false,
  coordinator: false,
  overlord: true,
});
Capabilities.NO_PROXY = new Capabilities({
  queryType: 'nativeAndSql',
  multiStageQueryTask: true,
  multiStageQueryDart: false,
  coordinator: false,
  overlord: false,
});
