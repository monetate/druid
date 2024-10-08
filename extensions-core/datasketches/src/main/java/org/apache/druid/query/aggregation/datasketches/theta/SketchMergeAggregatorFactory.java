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

package org.apache.druid.query.aggregation.datasketches.theta;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.apache.druid.jackson.DefaultTrueJsonIncludeFilter;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.query.aggregation.AggregatorFactoryNotMergeableException;
import org.apache.druid.query.aggregation.AggregatorUtil;
import org.apache.druid.segment.column.ColumnType;

import javax.annotation.Nullable;
import java.util.Objects;

public class SketchMergeAggregatorFactory extends SketchAggregatorFactory
{
  private final boolean shouldFinalize;
  private final boolean isInputThetaSketch;
  @Nullable
  private final Integer errorBoundsStdDev;

  @JsonCreator
  public SketchMergeAggregatorFactory(
      @JsonProperty("name") String name,
      @JsonProperty("fieldName") String fieldName,
      @JsonProperty("size") @Nullable Integer size,
      @JsonProperty("shouldFinalize") @Nullable Boolean shouldFinalize,
      @JsonProperty("isInputThetaSketch") @Nullable Boolean isInputThetaSketch,
      @JsonProperty("errorBoundsStdDev") @Nullable Integer errorBoundsStdDev
  )
  {
    super(name, fieldName, size, AggregatorUtil.SKETCH_MERGE_CACHE_TYPE_ID);
    this.shouldFinalize = (shouldFinalize == null) ? true : shouldFinalize;
    this.isInputThetaSketch = (isInputThetaSketch == null) ? false : isInputThetaSketch;
    this.errorBoundsStdDev = errorBoundsStdDev;
  }

  @Override
  public AggregatorFactory getCombiningFactory()
  {
    return new SketchMergeAggregatorFactory(name, name, size, shouldFinalize, false, errorBoundsStdDev);
  }

  @Override
  public AggregatorFactory getMergingFactory(AggregatorFactory other) throws AggregatorFactoryNotMergeableException
  {
    if (other.getName().equals(this.getName()) && other instanceof SketchMergeAggregatorFactory) {
      SketchMergeAggregatorFactory castedOther = (SketchMergeAggregatorFactory) other;

      return new SketchMergeAggregatorFactory(
          name,
          name,
          Math.max(size, castedOther.size),
          shouldFinalize,
          false,
          errorBoundsStdDev
      );
    } else {
      throw new AggregatorFactoryNotMergeableException(this, other);
    }
  }

  @JsonProperty
  @JsonInclude(value = JsonInclude.Include.CUSTOM, valueFilter = DefaultTrueJsonIncludeFilter.class)
  public boolean getShouldFinalize()
  {
    return shouldFinalize;
  }

  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_DEFAULT)
  public boolean getIsInputThetaSketch()
  {
    return isInputThetaSketch;
  }

  @Nullable
  @JsonProperty
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public Integer getErrorBoundsStdDev()
  {
    return errorBoundsStdDev;
  }

  /**
   * Finalize the computation on sketch object and returns estimate from underlying
   * sketch.
   *
   * @param object the sketch object
   *
   * @return sketch object
   */
  @Nullable
  @Override
  public Object finalizeComputation(@Nullable Object object)
  {
    if (object == null) {
      return null;
    }

    if (shouldFinalize) {
      SketchHolder holder = (SketchHolder) object;
      if (errorBoundsStdDev != null) {
        return holder.getEstimateWithErrorBounds(errorBoundsStdDev);
      } else {
        return holder.getEstimate();
      }
    } else {
      return object;
    }
  }

  /**
   * actual type is {@link SketchHolder}
   */
  @Override
  public ColumnType getIntermediateType()
  {
    return isInputThetaSketch ? SketchModule.MERGE_TYPE : SketchModule.BUILD_TYPE;
  }

  /**
   * if {@link #shouldFinalize} is set, actual type is {@link SketchEstimateWithErrorBounds} if
   * {@link #errorBoundsStdDev} is set.
   *
   * if {@link #shouldFinalize} is NOT set, type is {@link SketchHolder}
   */
  @Override
  public ColumnType getResultType()
  {
    if (shouldFinalize && errorBoundsStdDev == null) {
      return ColumnType.DOUBLE;
    }
    return getIntermediateType();
  }

  @Override
  public AggregatorFactory withName(String newName)
  {
    return new SketchMergeAggregatorFactory(
        newName,
        getFieldName(),
        getSize(),
        getShouldFinalize(),
        getIsInputThetaSketch(),
        getErrorBoundsStdDev()
    );
  }

  @Override
  public AggregatorFactory substituteCombiningFactory(AggregatorFactory preAggregated)
  {
    if (this == preAggregated) {
      return getCombiningFactory();
    }
    if (getClass() != preAggregated.getClass()) {
      return null;
    }
    SketchMergeAggregatorFactory that = (SketchMergeAggregatorFactory) preAggregated;
    if (Objects.equals(fieldName, that.fieldName) &&
        size <= that.size &&
        isInputThetaSketch == that.isInputThetaSketch
    ) {
      return getCombiningFactory();
    }
    return null;
  }

  @Override
  public boolean equals(Object o)
  {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }

    SketchMergeAggregatorFactory that = (SketchMergeAggregatorFactory) o;

    if (shouldFinalize != that.shouldFinalize) {
      return false;
    }

    if (errorBoundsStdDev == null ^ that.errorBoundsStdDev == null) {
      // one of the two stddevs (not both) are null
      return false;
    }

    if (errorBoundsStdDev != null && that.errorBoundsStdDev != null &&
        errorBoundsStdDev.intValue() != that.errorBoundsStdDev.intValue()) {
      // neither stddevs are null, Integer values don't match
      return false;
    }

    return isInputThetaSketch == that.isInputThetaSketch;
  }

  @Override
  public int hashCode()
  {
    int result = super.hashCode();
    result = 31 * result + (shouldFinalize ? 1 : 0);
    result = 31 * result + (isInputThetaSketch ? 1 : 0);
    result = 31 * result + (errorBoundsStdDev != null ? errorBoundsStdDev.hashCode() : 0);
    return result;
  }

  @Override
  public String toString()
  {
    return "SketchMergeAggregatorFactory{"
           + "fieldName=" + fieldName
           + ", name=" + name
           + ", size=" + size
           + ", shouldFinalize=" + shouldFinalize
           + ", isInputThetaSketch=" + isInputThetaSketch
           + ", errorBoundsStdDev=" + errorBoundsStdDev
           + "}";
  }
}
