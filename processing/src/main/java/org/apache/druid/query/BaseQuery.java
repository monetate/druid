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

package org.apache.druid.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import org.apache.druid.guice.annotations.ExtensionPoint;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.granularity.Granularities;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.granularity.PeriodGranularity;
import org.apache.druid.query.planning.ExecutionVertex;
import org.apache.druid.query.spec.QuerySegmentSpec;
import org.joda.time.DateTimeZone;
import org.joda.time.Duration;
import org.joda.time.Interval;

import javax.annotation.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 *
 */
@ExtensionPoint
public abstract class BaseQuery<T> implements Query<T>
{
  public static void checkInterrupted()
  {
    if (Thread.interrupted()) {
      throw new QueryInterruptedException(new InterruptedException());
    }
  }

  public static final String QUERY_ID = "queryId";
  public static final String SUB_QUERY_ID = "subQueryId";
  public static final String SQL_QUERY_ID = "sqlQueryId";
  private final DataSource dataSource;
  private final QueryContext context;
  private final QuerySegmentSpec querySegmentSpec;
  private volatile Duration duration;
  private final Granularity granularity;

  public BaseQuery(
      DataSource dataSource,
      QuerySegmentSpec querySegmentSpec,
      Map<String, Object> context
  )
  {
    this(dataSource, querySegmentSpec, context, Granularities.ALL);
  }

  public BaseQuery(
      DataSource dataSource,
      QuerySegmentSpec querySegmentSpec,
      Map<String, Object> context,
      Granularity granularity
  )
  {
    Preconditions.checkNotNull(dataSource, "dataSource can't be null");
    Preconditions.checkNotNull(querySegmentSpec, "querySegmentSpec can't be null");
    Preconditions.checkNotNull(granularity, "Must specify a granularity");

    this.dataSource = dataSource;
    this.context = QueryContext.of(context);
    this.querySegmentSpec = querySegmentSpec;
    this.granularity = granularity;
  }

  @JsonProperty
  @Override
  public DataSource getDataSource()
  {
    return dataSource;
  }

  @JsonProperty("intervals")
  public QuerySegmentSpec getQuerySegmentSpec()
  {
    return querySegmentSpec;
  }

  @Override
  public QueryRunner<T> getRunner(QuerySegmentWalker walker)
  {
    ExecutionVertex ev = ExecutionVertex.of(this);
    return ev.getEffectiveQuerySegmentSpec().lookup(this, walker);
  }

  @Override
  public List<Interval> getIntervals()
  {
    return querySegmentSpec.getIntervals();
  }

  @Override
  public Duration getDuration()
  {
    if (duration == null) {
      duration = calculateDuration(querySegmentSpec.getIntervals());
    }
    return duration;
  }

  public static Duration calculateDuration(Collection<Interval> intervals)
  {
    try {
      Duration totalDuration = new Duration(0);
      for (Interval interval : intervals) {
        if (interval != null) {
          totalDuration = totalDuration.plus(interval.toDuration());
        }
      }
      return totalDuration;
    }
    catch (ArithmeticException e) {
      // Overflow due to addition. Return the largest duration possible.
      return Intervals.ETERNITY.toDuration();
    }
  }

  @Override
  @JsonProperty
  public Granularity getGranularity()
  {
    return granularity;
  }

  @Override
  public DateTimeZone getTimezone()
  {
    return granularity instanceof PeriodGranularity
           ? ((PeriodGranularity) granularity).getTimeZone()
           : DateTimeZone.UTC;
  }

  @Override
  @JsonProperty
  @JsonInclude(Include.NON_DEFAULT)
  public Map<String, Object> getContext()
  {
    return context.asMap();
  }

  @Override
  public QueryContext context()
  {
    return context;
  }

  /**
   * @deprecated use {@link #computeOverriddenContext(Map, Map) computeOverriddenContext(getContext(), overrides))}
   * instead. This method may be removed in the next minor or major version of Druid.
   */
  @Deprecated
  protected Map<String, Object> computeOverridenContext(final Map<String, Object> overrides)
  {
    return computeOverriddenContext(getContext(), overrides);
  }

  public static Map<String, Object> computeOverriddenContext(
      final Map<String, Object> context,
      final Map<String, Object> overrides
  )
  {
    return QueryContexts.override(context, overrides);
  }

  /**
   * Default implementation of {@link Query#getResultOrdering()} that uses {@link Ordering#natural()}.
   *
   * If your query result type T is not Comparable, you must override this method.
   */
  @Override
  @SuppressWarnings("unchecked") // assumes T is Comparable; see method javadoc
  public Ordering<T> getResultOrdering()
  {
    return (Ordering<T>) Ordering.natural();
  }

  @Nullable
  @Override
  public String getId()
  {
    return context().getString(QUERY_ID);
  }

  @Override
  public Query<T> withSubQueryId(String subQueryId)
  {
    return withOverriddenContext(ImmutableMap.of(SUB_QUERY_ID, subQueryId));
  }

  @Nullable
  @Override
  public String getSubQueryId()
  {
    return context().getString(SUB_QUERY_ID);
  }

  @Override
  public Query<T> withId(String id)
  {
    return withOverriddenContext(ImmutableMap.of(QUERY_ID, id));
  }

  @Override
  public Query<T> withSqlQueryId(String sqlQueryId)
  {
    return withOverriddenContext(ImmutableMap.of(SQL_QUERY_ID, sqlQueryId));
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
    BaseQuery<?> baseQuery = (BaseQuery<?>) o;

    // Must use getDuration() instead of "duration" because duration is lazily computed.
    return Objects.equals(dataSource, baseQuery.dataSource) &&
           Objects.equals(context, baseQuery.context) &&
           Objects.equals(querySegmentSpec, baseQuery.querySegmentSpec) &&
           Objects.equals(getDuration(), baseQuery.getDuration()) &&
           Objects.equals(granularity, baseQuery.granularity);
  }

  @Override
  public int hashCode()
  {
    // Must use getDuration() instead of "duration" because duration is lazily computed.
    return Objects.hash(dataSource, context, querySegmentSpec, getDuration(), granularity);
  }
}
