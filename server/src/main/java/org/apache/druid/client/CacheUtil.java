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

package org.apache.druid.client;

import org.apache.druid.client.cache.Cache;
import org.apache.druid.client.cache.CacheConfig;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.query.CacheStrategy;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryToolChest;
import org.apache.druid.query.SegmentDescriptor;
import org.joda.time.Interval;

import javax.annotation.Nullable;

import java.nio.ByteBuffer;

public class CacheUtil
{
  private static final String RESULT_CACHE_NS = "RES";

  public enum ServerType
  {
    BROKER {
      @Override
      boolean willMergeRunners()
      {
        return false;
      }
    },
    DATA {
      @Override
      boolean willMergeRunners()
      {
        return true;
      }
    };

    /**
     * Same meaning as the "willMergeRunners" parameter to {@link CacheStrategy#isCacheable}.
     */
    abstract boolean willMergeRunners();
  }

  public static Cache.NamedKey computeResultLevelCacheKey(byte[] resultLevelCacheIdentifier)
  {
    return new Cache.NamedKey(RESULT_CACHE_NS, resultLevelCacheIdentifier);
  }

  public static Cache.NamedKey computeSegmentCacheKey(
      String segmentId,
      SegmentDescriptor descriptor,
      byte[] queryCacheKey
  )
  {
    final Interval segmentQueryInterval = descriptor.getInterval();
    final byte[] versionBytes = StringUtils.toUtf8(descriptor.getVersion());

    return new Cache.NamedKey(
        segmentId,
        ByteBuffer
            .allocate(16 + versionBytes.length + 4 + queryCacheKey.length)
            .putLong(segmentQueryInterval.getStartMillis())
            .putLong(segmentQueryInterval.getEndMillis())
            .put(versionBytes)
            .putInt(descriptor.getPartitionNumber())
            .put(queryCacheKey)
            .array()
    );
  }

  /**
   * Returns whether the segment-level cache should be checked for a particular query.
   *
   * @param query         the query to check
   * @param cacheStrategy result of {@link QueryToolChest#getCacheStrategy} on this query
   * @param cacheConfig   current active cache config
   * @param serverType    BROKER or DATA
   */
  public static <T> boolean isUseSegmentCache(
      Query<T> query,
      @Nullable CacheStrategy<T, Object, Query<T>> cacheStrategy,
      CacheConfig cacheConfig,
      ServerType serverType
  )
  {
    return cacheConfig.isUseCache()
           && query.context().isUseCache()
           && isQueryCacheable(query, cacheStrategy, cacheConfig, serverType, true);
  }

  /**
   * Returns whether the result-level cache should be populated for a particular query.
   *
   * @param query         the query to check
   * @param cacheStrategy result of {@link QueryToolChest#getCacheStrategy} on this query
   * @param cacheConfig   current active cache config
   * @param serverType    BROKER or DATA
   */
  public static <T> boolean isPopulateSegmentCache(
      Query<T> query,
      @Nullable CacheStrategy<T, Object, Query<T>> cacheStrategy,
      CacheConfig cacheConfig,
      ServerType serverType
  )
  {
    return isQueryCacheable(query, cacheStrategy, cacheConfig, serverType, true)
           && query.context().isPopulateCache()
           && cacheConfig.isPopulateCache();
  }

  /**
   * Returns whether the result-level cache should be checked for a particular query.
   *
   * @param query         the query to check
   * @param cacheStrategy result of {@link QueryToolChest#getCacheStrategy} on this query
   * @param cacheConfig   current active cache config
   * @param serverType    BROKER or DATA
   */
  public static <T> boolean isUseResultCache(
      Query<T> query,
      @Nullable CacheStrategy<T, Object, Query<T>> cacheStrategy,
      CacheConfig cacheConfig,
      ServerType serverType
  )
  {
    return isQueryCacheable(query, cacheStrategy, cacheConfig, serverType, false)
           && query.context().isUseResultLevelCache()
           && cacheConfig.isUseResultLevelCache();
  }

  /**
   * Returns whether the result-level cache should be populated for a particular query.
   *
   * @param query         the query to check
   * @param cacheStrategy result of {@link QueryToolChest#getCacheStrategy} on this query
   * @param cacheConfig   current active cache config
   * @param serverType    BROKER or DATA
   */
  public static <T> boolean isPopulateResultCache(
      Query<T> query,
      @Nullable CacheStrategy<T, Object, Query<T>> cacheStrategy,
      CacheConfig cacheConfig,
      ServerType serverType
  )
  {
    return isQueryCacheable(query, cacheStrategy, cacheConfig, serverType, false)
           && query.context().isPopulateResultLevelCache()
           && cacheConfig.isPopulateResultLevelCache();
  }

  /**
   * Returns whether a particular query is cacheable. Does not check whether we are actually configured to use or
   * populate the cache; that should be done separately.
   *
   * @param query         the query to check
   * @param cacheStrategy result of {@link QueryToolChest#getCacheStrategy} on this query
   * @param cacheConfig   current active cache config
   * @param serverType    BROKER or DATA
   * @param bySegment     segement level or result-level cache
   */
  static <T> boolean isQueryCacheable(
      final Query<T> query,
      @Nullable final CacheStrategy<T, Object, Query<T>> cacheStrategy,
      final CacheConfig cacheConfig,
      final ServerType serverType,
      final boolean bySegment
  )
  {
    return cacheStrategy != null
           && cacheStrategy.isCacheable(query, serverType.willMergeRunners(), bySegment)
           && cacheConfig.isQueryCacheable(query)
           && query.getDataSource().isCacheable(serverType == ServerType.BROKER);
  }
}
