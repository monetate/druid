/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.msq.indexing;

import com.fasterxml.jackson.annotation.JacksonInject;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Injector;
import com.google.inject.Key;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.druid.client.indexing.ClientTaskQuery;
import org.apache.druid.guice.annotations.EscalatedGlobal;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexing.common.TaskLock;
import org.apache.druid.indexing.common.TaskLockType;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.indexing.common.actions.TimeChunkLockTryAcquireAction;
import org.apache.druid.indexing.common.config.TaskConfig;
import org.apache.druid.indexing.common.task.AbstractTask;
import org.apache.druid.indexing.common.task.PendingSegmentAllocatingTask;
import org.apache.druid.indexing.common.task.Tasks;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.msq.exec.Controller;
import org.apache.druid.msq.exec.ControllerContext;
import org.apache.druid.msq.exec.ControllerImpl;
import org.apache.druid.msq.exec.MSQTasks;
import org.apache.druid.msq.exec.ResultsContext;
import org.apache.druid.msq.indexing.destination.DataSourceMSQDestination;
import org.apache.druid.msq.indexing.destination.DurableStorageMSQDestination;
import org.apache.druid.msq.indexing.destination.ExportMSQDestination;
import org.apache.druid.msq.indexing.destination.MSQDestination;
import org.apache.druid.msq.indexing.destination.TaskReportMSQDestination;
import org.apache.druid.msq.indexing.error.CancellationReason;
import org.apache.druid.msq.sql.MSQTaskQueryKitSpecFactory;
import org.apache.druid.msq.util.MultiStageQueryContext;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryContext;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.rpc.ServiceClientFactory;
import org.apache.druid.rpc.StandardRetryPolicy;
import org.apache.druid.rpc.indexing.OverlordClient;
import org.apache.druid.segment.column.ColumnType;
import org.apache.druid.server.coordination.BroadcastDatasourceLoadingSpec;
import org.apache.druid.server.lookup.cache.LookupLoadingSpec;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.sql.calcite.run.SqlResults;
import org.joda.time.Interval;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@JsonTypeName(MSQControllerTask.TYPE)
public class MSQControllerTask extends AbstractTask implements ClientTaskQuery, PendingSegmentAllocatingTask
{
  public static final String TYPE = "query_controller";
  public static final String DUMMY_DATASOURCE_FOR_SELECT = "__query_select";
  public static final String DUMMY_DATASOURCE_FOR_EXPORT = "__query_export";
  private static final Logger log = new Logger(MSQControllerTask.class);

  private final LegacyMSQSpec querySpec;

  /**
   * Enables users, and the web console, to see the original SQL query (if any). Not used by anything else in Druid.
   */
  @Nullable
  private final String sqlQuery;

  /**
   * Enables users, and the web console, to see the original SQL context (if any). Not used by any other Druid logic.
   */
  @Nullable
  private final Map<String, Object> sqlQueryContext;

  /**
   * Enables usage of {@link SqlResults#coerce(ObjectMapper, SqlResults.Context, Object, SqlTypeName, String)}.
   */
  @Nullable
  private final SqlResults.Context sqlResultsContext;

  /**
   * SQL type names for each field in the resultset.
   */
  @Nullable
  private final List<SqlTypeName> sqlTypeNames;

  @Nullable
  private final List<ColumnType> nativeTypeNames;

  // Using an Injector directly because tasks do not have a way to provide their own Guice modules.
  @JacksonInject
  private Injector injector;

  private volatile Controller controller;

  @JsonCreator
  public MSQControllerTask(
      @JsonProperty("id") @Nullable String id,
      @JsonProperty("spec") LegacyMSQSpec querySpec,
      @JsonProperty("sqlQuery") @Nullable String sqlQuery,
      @JsonProperty("sqlQueryContext") @Nullable Map<String, Object> sqlQueryContext,
      @JsonProperty("sqlResultsContext") @Nullable SqlResults.Context sqlResultsContext,
      @JsonProperty("sqlTypeNames") @Nullable List<SqlTypeName> sqlTypeNames,
      @JsonProperty("nativeTypeNames") @Nullable List<ColumnType> nativeTypeNames,
      @JsonProperty("context") @Nullable Map<String, Object> context
  )
  {
    super(
        id != null ? id : MSQTasks.controllerTaskId(null),
        id,
        null,
        getDataSourceForTaskMetadata(querySpec),
        context
    );

    this.querySpec = querySpec;
    this.sqlQuery = sqlQuery;
    this.sqlQueryContext = sqlQueryContext;
    this.sqlResultsContext = sqlResultsContext;
    this.sqlTypeNames = sqlTypeNames;
    this.nativeTypeNames = nativeTypeNames;

    addToContext(Tasks.FORCE_TIME_CHUNK_LOCK_KEY, true);
  }

  public MSQControllerTask(
      @Nullable String id,
      LegacyMSQSpec querySpec,
      @Nullable String sqlQuery,
      @Nullable Map<String, Object> sqlQueryContext,
      @Nullable SqlResults.Context sqlResultsContext,
      @Nullable List<SqlTypeName> sqlTypeNames,
      @Nullable List<ColumnType> nativeTypeNames,
      @Nullable Map<String, Object> context,
      Injector injector
  )
  {
    this(id, querySpec, sqlQuery, sqlQueryContext, sqlResultsContext, sqlTypeNames, nativeTypeNames, context);
    this.injector = injector;
  }

  @Override
  public String getType()
  {
    return TYPE;
  }

  @Nonnull
  @JsonIgnore
  @Override
  public Set<ResourceAction> getInputSourceResources()
  {
    // the input sources are properly computed in the SQL / calcite layer, but not in the native MSQ task here.
    return ImmutableSet.of();
  }

  @Override
  public String getTaskAllocatorId()
  {
    return getId();
  }

  @JsonProperty("spec")
  public LegacyMSQSpec getQuerySpec()
  {
    return querySpec;
  }

  @Nullable
  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public List<SqlTypeName> getSqlTypeNames()
  {
    return sqlTypeNames;
  }


  @Nullable
  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public List<ColumnType> getNativeTypeNames()
  {
    return nativeTypeNames;
  }

  @Nullable
  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String getSqlQuery()
  {
    return sqlQuery;
  }

  @Nullable
  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private Map<String, Object> getSqlQueryContext()
  {
    return sqlQueryContext;
  }

  @Nullable
  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public SqlResults.Context getSqlResultsContext()
  {
    return sqlResultsContext;
  }

  @Override
  public boolean isReady(TaskActionClient taskActionClient) throws Exception
  {
    // If we're in replace mode, acquire locks for all intervals before declaring the task ready.
    if (isIngestion(querySpec) && ((DataSourceMSQDestination) querySpec.getDestination()).isReplaceTimeChunks()) {
      final TaskLockType taskLockType = getTaskLockType();
      final List<Interval> intervals =
          ((DataSourceMSQDestination) querySpec.getDestination()).getReplaceTimeChunks();
      log.debug(
          "Task[%s] trying to acquire[%s] locks for intervals[%s] to become ready",
          getId(),
          taskLockType,
          intervals
      );
      for (final Interval interval : intervals) {
        final TaskLock taskLock =
            taskActionClient.submit(new TimeChunkLockTryAcquireAction(taskLockType, interval));

        if (taskLock == null) {
          return false;
        }
        taskLock.assertNotRevoked();
      }
    }

    return true;
  }

  @Override
  public TaskStatus runTask(final TaskToolbox toolbox) throws Exception
  {
    final ServiceClientFactory clientFactory =
        injector.getInstance(Key.get(ServiceClientFactory.class, EscalatedGlobal.class));
    final OverlordClient overlordClient = injector.getInstance(OverlordClient.class)
                                                  .withRetryPolicy(StandardRetryPolicy.unlimited());
    final ControllerContext context = new IndexerControllerContext(
        this,
        toolbox,
        injector,
        clientFactory,
        overlordClient
    );

    controller = new ControllerImpl(
        querySpec,
        new ResultsContext(getSqlTypeNames(), getSqlResultsContext()),
        context,
        injector.getInstance(MSQTaskQueryKitSpecFactory.class)
    );

    final TaskReportQueryListener queryListener = new TaskReportQueryListener(
        querySpec.getDestination(),
        () -> toolbox.getTaskReportFileWriter().openReportOutputStream(getId()),
        toolbox.getJsonMapper(),
        getId(),
        getContext()
    );

    controller.run(queryListener);
    return queryListener.getStatusReport().toTaskStatus(getId());
  }

  @Override
  public void stopGracefully(final TaskConfig taskConfig)
  {
    if (controller != null) {
      controller.stop(CancellationReason.TASK_SHUTDOWN);
    }
  }

  @Override
  public int getPriority()
  {
    return getContextValue(Tasks.PRIORITY_KEY, Tasks.DEFAULT_BATCH_INDEX_TASK_PRIORITY);
  }

  @Nullable
  public TaskLockType getTaskLockType()
  {
    if (isIngestion(querySpec)) {
      return MultiStageQueryContext.validateAndGetTaskLockType(
          QueryContext.of(
              // Use the task context and override with the query context
              QueryContexts.override(
                  getContext(),
                  querySpec.getContext().asMap()
              )
          ),
          ((DataSourceMSQDestination) querySpec.getDestination()).isReplaceTimeChunks()
      );
    } else {
      // Locks need to be acquired only if data is being ingested into a DataSource
      return null;
    }
  }

  private static String getDataSourceForTaskMetadata(final LegacyMSQSpec querySpec)
  {
    final MSQDestination destination = querySpec.getDestination();

    if (destination instanceof DataSourceMSQDestination) {
      return ((DataSourceMSQDestination) destination).getDataSource();
    } else if (destination instanceof ExportMSQDestination) {
      return DUMMY_DATASOURCE_FOR_EXPORT;
    } else {
      return DUMMY_DATASOURCE_FOR_SELECT;
    }
  }

  @Override
  public Optional<Resource> getDestinationResource()
  {
    return querySpec.getDestination().getDestinationResource();
  }

  /**
   * Checks whether the task is an ingestion into a Druid datasource.
   */
  public static boolean isIngestion(final MSQSpec querySpec)
  {
    return isIngestion(querySpec.getDestination());
  }

  /**
   * Checks whether the task is an ingestion into a Druid datasource.
   */
  public static boolean isIngestion(MSQDestination destination)
  {
    return destination instanceof DataSourceMSQDestination;
  }

  /**
   * Checks whether the task is an export into external files.
   */
  public static boolean isExport(MSQDestination destination)
  {
    return destination instanceof ExportMSQDestination;
  }

  /**
   * Checks whether the task is an async query which writes frame files containing the final results into durable storage.
   */
  public static boolean writeFinalStageResultsToDurableStorage(final MSQDestination destination)
  {
    return destination instanceof DurableStorageMSQDestination;
  }

  /**
   * Checks whether the task is an async query which writes frame files containing the final results into durable storage.
   */
  public static boolean writeFinalResultsToTaskReport(final MSQDestination destination)
  {
    return destination instanceof TaskReportMSQDestination;
  }

  /**
   * Returns true if the task reads from the same table as the destination. In this case, we would prefer to fail
   * instead of reading any unused segments to ensure that old data is not read.
   */
  public static boolean isReplaceInputDataSourceTask(Query<?> query, MSQDestination destination)
  {
    if (isIngestion(destination)) {
      final String targetDataSource = ((DataSourceMSQDestination) destination).getDataSource();
      final Set<String> sourceTableNames = query.getDataSource().getTableNames();
      return sourceTableNames.contains(targetDataSource);
    } else {
      return false;
    }
  }

  @Override
  public LookupLoadingSpec getLookupLoadingSpec()
  {
    return LookupLoadingSpec.NONE;
  }

  @Override
  public BroadcastDatasourceLoadingSpec getBroadcastDatasourceLoadingSpec()
  {
    return BroadcastDatasourceLoadingSpec.NONE;
  }
}
