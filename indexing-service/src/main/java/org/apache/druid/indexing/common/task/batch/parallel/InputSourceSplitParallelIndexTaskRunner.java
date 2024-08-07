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

import org.apache.druid.data.input.InputSource;
import org.apache.druid.data.input.InputSplit;
import org.apache.druid.data.input.impl.SplittableInputSource;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.task.Task;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

/**
 * Base class for different implementations of {@link ParallelIndexTaskRunner} that operate on
 * {@link InputSource} splits.
 */
abstract class InputSourceSplitParallelIndexTaskRunner<T extends Task, R extends SubTaskReport>
    extends ParallelIndexPhaseRunner<T, R>
{
  private final ParallelIndexIngestionSpec ingestionSchema;
  private final SplittableInputSource<?> baseInputSource;

  InputSourceSplitParallelIndexTaskRunner(
      TaskToolbox toolbox,
      String taskId,
      String groupId,
      String baseSubtaskSpecName,
      ParallelIndexIngestionSpec ingestionSchema,
      Map<String, Object> context
  )
  {
    super(
        toolbox,
        taskId,
        groupId,
        baseSubtaskSpecName,
        ingestionSchema.getTuningConfig(),
        context
    );
    this.ingestionSchema = ingestionSchema;
    this.baseInputSource = (SplittableInputSource) ingestionSchema.getIOConfig().getNonNullInputSource(toolbox);
  }

  @Override
  Iterator<SubTaskSpec<T>> subTaskSpecIterator() throws IOException
  {
    return baseInputSource.createSplits(
        ingestionSchema.getIOConfig().getInputFormat(),
        getTuningConfig().getSplitHintSpec()
    ).map(this::newTaskSpec).iterator();
  }

  @Override
  final int estimateTotalNumSubTasks() throws IOException
  {
    return baseInputSource.estimateNumSplits(
        ingestionSchema.getIOConfig().getInputFormat(),
        getTuningConfig().getSplitHintSpec()
    );
  }

  final SubTaskSpec<T> newTaskSpec(InputSplit split)
  {
    final InputSource inputSource;
    inputSource = baseInputSource.withSplit(split);
    final ParallelIndexIngestionSpec subTaskIngestionSpec = new ParallelIndexIngestionSpec(
        ingestionSchema.getDataSchema(),
        new ParallelIndexIOConfig(
            inputSource,
            ingestionSchema.getIOConfig().getInputFormat(),
            ingestionSchema.getIOConfig().isAppendToExisting(),
            ingestionSchema.getIOConfig().isDropExisting()
        ),
        ingestionSchema.getTuningConfig()
    );

    return createSubTaskSpec(
        getBaseSubtaskSpecName() + "_" + getAndIncrementNextSpecId(),
        getGroupId(),
        getTaskId(),
        getContext(),
        split,
        subTaskIngestionSpec
    );
  }

  /**
   * @return Ingestion spec split suitable for this parallel worker
   */
  abstract SubTaskSpec<T> createSubTaskSpec(
      String id,
      String groupId,
      String supervisorTaskId,
      Map<String, Object> context,
      InputSplit split,
      ParallelIndexIngestionSpec subTaskIngestionSpec
  );
}
