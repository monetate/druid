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

package org.apache.druid.indexing.common.task.batch.parallel;

import com.google.common.collect.ImmutableMap;
import org.apache.druid.data.input.InputFormat;
import org.apache.druid.data.input.InputSource;
import org.apache.druid.data.input.StringTuple;
import org.apache.druid.data.input.impl.InlineInputSource;
import org.apache.druid.data.input.impl.JsonInputFormat;
import org.apache.druid.indexer.partitions.DimensionRangePartitionsSpec;
import org.apache.druid.indexer.partitions.DynamicPartitionsSpec;
import org.apache.druid.indexer.partitions.HashedPartitionsSpec;
import org.apache.druid.indexer.partitions.PartitionsSpec;
import org.apache.druid.indexing.common.task.TuningConfigBuilder;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.segment.TestHelper;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.server.security.Action;
import org.apache.druid.server.security.Resource;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.server.security.ResourceType;
import org.apache.druid.timeline.partition.PartitionBoundaries;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import java.util.Collections;

public class PartialRangeSegmentGenerateTaskTest extends AbstractParallelIndexSupervisorTaskTest
{
  @Rule
  public ExpectedException exception = ExpectedException.none();

  public PartialRangeSegmentGenerateTaskTest()
  {
    // We don't need to emulate transient failures for this test.
    super(0.0, 0.0);
  }

  @Test
  public void requiresForceGuaranteedRollup()
  {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("range or single_dim partitionsSpec required");

    ParallelIndexTuningConfig tuningConfig = TuningConfigBuilder
        .forParallelIndexTask()
        .withPartitionsSpec(new DynamicPartitionsSpec(null, null))
        .build();
    new PartialRangeSegmentGenerateTaskBuilder()
        .tuningConfig(tuningConfig)
        .build();
  }

  @Test
  public void requiresMultiDimensionPartitions()
  {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("range or single_dim partitionsSpec required");

    PartitionsSpec partitionsSpec = new HashedPartitionsSpec(null, 1, null);
    ParallelIndexTuningConfig tuningConfig = TuningConfigBuilder
        .forParallelIndexTask()
        .withForceGuaranteedRollup(true)
        .withPartitionsSpec(partitionsSpec)
        .build();

    new PartialRangeSegmentGenerateTaskBuilder()
        .tuningConfig(tuningConfig)
        .build();
  }

  @Test
  public void requiresGranularitySpecInputIntervals()
  {
    exception.expect(IllegalArgumentException.class);
    exception.expectMessage("Missing intervals in granularitySpec");

    DataSchema dataSchema = ParallelIndexTestingFactory.createDataSchema(Collections.emptyList());

    new PartialRangeSegmentGenerateTaskBuilder()
        .dataSchema(dataSchema)
        .build();
  }

  @Test
  public void serializesDeserializes()
  {
    PartialRangeSegmentGenerateTask task = new PartialRangeSegmentGenerateTaskBuilder().build();
    TestHelper.testSerializesDeserializes(getObjectMapper(), task);
  }

  @Test
  public void hasCorrectInputSourceResources()
  {
    PartialRangeSegmentGenerateTask task = new PartialRangeSegmentGenerateTaskBuilder().build();
    Assert.assertEquals(
        Collections.singleton(
            new ResourceAction(new Resource(
                InlineInputSource.TYPE_KEY,
                ResourceType.EXTERNAL
            ), Action.READ)),
        task.getInputSourceResources()
    );
  }

  @Test
  public void hasCorrectPrefixForAutomaticId()
  {
    PartialRangeSegmentGenerateTask task = new PartialRangeSegmentGenerateTaskBuilder().build();
    Assert.assertTrue(task.getId().startsWith(PartialRangeSegmentGenerateTask.TYPE));
  }

  private static class PartialRangeSegmentGenerateTaskBuilder
  {
    private static final InputSource INPUT_SOURCE = new InlineInputSource("data");
    private static final InputFormat INPUT_FORMAT = new JsonInputFormat(null, null, null, null, null);

    private ParallelIndexTuningConfig tuningConfig = TuningConfigBuilder
        .forParallelIndexTask()
        .withForceGuaranteedRollup(true)
        .withPartitionsSpec(
            new DimensionRangePartitionsSpec(null, 1000, Collections.singletonList("dim"), false)
        )
        .build();
    private DataSchema dataSchema =
        ParallelIndexTestingFactory.createDataSchema(ParallelIndexTestingFactory.INPUT_INTERVALS);

    PartialRangeSegmentGenerateTaskBuilder tuningConfig(ParallelIndexTuningConfig tuningConfig)
    {
      this.tuningConfig = tuningConfig;
      return this;
    }

    PartialRangeSegmentGenerateTaskBuilder dataSchema(DataSchema dataSchema)
    {
      this.dataSchema = dataSchema;
      return this;
    }

    PartialRangeSegmentGenerateTask build()
    {
      ParallelIndexIngestionSpec ingestionSpec =
          ParallelIndexTestingFactory.createIngestionSpec(INPUT_SOURCE, INPUT_FORMAT, tuningConfig, dataSchema);

      return new PartialRangeSegmentGenerateTask(
          ParallelIndexTestingFactory.AUTOMATIC_ID,
          ParallelIndexTestingFactory.GROUP_ID,
          ParallelIndexTestingFactory.TASK_RESOURCE,
          ParallelIndexTestingFactory.SUPERVISOR_TASK_ID,
          ParallelIndexTestingFactory.SUBTASK_SPEC_ID,
          ParallelIndexTestingFactory.NUM_ATTEMPTS,
          ingestionSpec,
          ParallelIndexTestingFactory.CONTEXT,
          ImmutableMap.of(Intervals.ETERNITY, new PartitionBoundaries(StringTuple.create("a")))
      );
    }
  }
}
