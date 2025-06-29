/*
 * Copyright 2014-2025 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spectator.atlas.impl;

import com.netflix.spectator.api.Id;
import com.netflix.spectator.api.Registry;
import com.netflix.spectator.impl.Cache;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Index that to efficiently match an {@link com.netflix.spectator.api.Id} against a set of
 * queries that are known in advance. The index is thread safe for queries. Updates to the
 * index should be done from a single thread at a time.
 */
@SuppressWarnings("PMD.LinguisticNaming")
public final class QueryIndex<T> {

  /**
   * Supplier to create a new instance of a cache used for other checks. The default should
   * be fine for most uses, but heavy uses with many expressions and high throughput may
   * benefit from an alternate implementation.
   */
  public interface CacheSupplier<V> extends Supplier<Cache<String, List<QueryIndex<V>>>> {
  }

  /** Default supplier based on a simple LFU cache. */
  public static class DefaultCacheSupplier<V> implements CacheSupplier<V> {

    private final Registry registry;

    DefaultCacheSupplier(Registry registry) {
      this.registry = registry;
    }

    @Override
    public Cache<String, List<QueryIndex<V>>> get() {
      return Cache.lfu(registry, "QueryIndex", 100, 1000);
    }
  }

  /**
   * Dedup values as they are consumed. This is used to avoid processing the same result
   * multiple times in the case of OR clauses where multiple match the same data point.
   */
  private static class DedupConsumer<T> implements Consumer<T> {

    private final Consumer<T> consumer;
    private final Set<T> alreadySeen;

    DedupConsumer(Consumer<T> consumer) {
      this.consumer = consumer;
      this.alreadySeen = new HashSet<>();
    }

    @Override public void accept(T t) {
      if (alreadySeen.add(t)) {
        consumer.accept(t);
      }
    }
  }

  /**
   * Return a new instance of an index that is empty. The default caching behavior will be
   * used.
   */
  public static <V> QueryIndex<V> newInstance(Registry registry) {
    return newInstance(new DefaultCacheSupplier<>(registry));
  }

  /**
   * Return a new instance of an index that is empty. The caches will be used to cache the
   * results of regex or other checks to try and avoid scans with repeated string values
   * across many ids.
   */
  public static <V> QueryIndex<V> newInstance(CacheSupplier<V> cacheSupplier) {
    return new QueryIndex<>(cacheSupplier, "name");
  }

  /**
   * Return a new instance of an index that is empty and doesn't have an explicit key set.
   * Used internally rather than {@link #newInstance(CacheSupplier)} which sets the key to {@code name}
   * so the root node will be correct for traversing the id.
   */
  private static <V> QueryIndex<V> empty(CacheSupplier<V> cacheSupplier) {
    return new QueryIndex<>(cacheSupplier, null);
  }

  /**
   * Compare the strings and put {@code name} first and then normally sort the other keys.
   * This allows the {@link Id} to be traversed in order while performing the lookup.
   */
  private static int compare(String k1, String k2) {
    if ("name".equals(k1)) {
      return "name".equals(k2) ? 0 : -1;
    } else if ("name".equals(k2)) {
      return 1;
    } else {
      return k1.compareTo(k2);
    }
  }

  private final CacheSupplier<T> cacheSupplier;

  private volatile String key;

  // Checks for :eq clauses
  private final ConcurrentHashMap<String, QueryIndex<T>> equalChecks;

  // Checks for other key queries, e.g. :re, :in, :gt, :lt, etc. Prefix tree is used to
  // filter regex and in clauses. The matching is cached to avoid expensive regex checks
  // as much as possible.
  private final ConcurrentHashMap<Query.KeyQuery, QueryIndex<T>> otherChecks;
  private final PrefixTree otherChecksTree;
  private final Cache<String, List<QueryIndex<T>>> otherChecksCache;

  // Index for :has queries
  private volatile QueryIndex<T> hasKeyIdx;

  // Index for queries that do not have a clause for a given key
  private volatile QueryIndex<T> otherKeysIdx;

  // Index for :not queries to capture entries where a key is missing
  private volatile QueryIndex<T> missingKeysIdx;

  // Matches for this level of the tree
  private final Set<T> matches;

  /** Create a new instance. */
  private QueryIndex(CacheSupplier<T> cacheSupplier, String key) {
    this.cacheSupplier = cacheSupplier;
    this.key = key;
    this.equalChecks = new ConcurrentHashMap<>();
    this.otherChecks = new ConcurrentHashMap<>();
    this.otherChecksTree = new PrefixTree();
    this.otherChecksCache = cacheSupplier.get();
    this.hasKeyIdx = null;
    this.otherKeysIdx = null;
    this.missingKeysIdx = null;
    this.matches = new CopyOnWriteArraySet<>();
  }

  private List<Query.KeyQuery> sort(Query query) {
    List<Query.KeyQuery> result = new ArrayList<>();
    for (Query q : query.andList()) {
      result.add((Query.KeyQuery) q);
    }
    result.sort((q1, q2) -> compare(q1.key(), q2.key()));
    return result;
  }

  /**
   * Add a value that should match for the specified query.
   *
   * @param query
   *     Query that corresponds to the value.
   * @param value
   *     Value to return for ids that match the query.
   * @return
   *     This index so it can be used in a fluent manner.
   */
  public QueryIndex<T> add(Query query, T value) {
    for (Query q : query.dnfList()) {
      if (q == Query.TRUE) {
        matches.add(value);
      } else if (q == Query.FALSE) {
        break;
      } else {
        add(sort(q), 0, value);
      }
    }
    return this;
  }

  private void add(List<Query.KeyQuery> queries, int i, T value) {
    if (i < queries.size()) {
      Query.KeyQuery kq = queries.get(i);

      // Check for additional queries based on the same key and combine into a
      // composite if needed
      Query.CompositeKeyQuery composite = null;
      int j = i + 1;
      while (j < queries.size()) {
        Query.KeyQuery q = queries.get(j);
        if (kq.key().equals(q.key())) {
          if (composite == null) {
            composite = new Query.CompositeKeyQuery(kq);
            kq = composite;
          }
          composite.add(q);
          ++j;
        } else {
          break;
        }
      }

      if (key == null) {
        key = kq.key();
      }

      if (key.equals(kq.key())) {
        if (kq instanceof Query.Equal) {
          String v = ((Query.Equal) kq).value();
          QueryIndex<T> idx = equalChecks.computeIfAbsent(v, id -> QueryIndex.empty(cacheSupplier));
          idx.add(queries, j, value);
        } else if (kq instanceof Query.Has) {
          if (hasKeyIdx == null) {
            hasKeyIdx = QueryIndex.empty(cacheSupplier);
          }
          hasKeyIdx.add(queries, j, value);
        } else {
          QueryIndex<T> idx = otherChecks.computeIfAbsent(kq, id -> QueryIndex.empty(cacheSupplier));
          idx.add(queries, j, value);
          if (otherChecksTree.put(kq)) {
            otherChecksCache.clear();
          }

          // Not queries should match if the key is missing from the id, so they need to
          // be included in the other keys sub-tree as well. Check this by seeing if it will
          // match an empty map as there could be a variety of inverted types.
          if (kq.matches(Collections.emptyMap())) {
            if (missingKeysIdx == null) {
              missingKeysIdx = QueryIndex.empty(cacheSupplier);
            }
            missingKeysIdx.add(queries, j, value);
          }
        }
      } else {
        if (otherKeysIdx == null) {
          otherKeysIdx = QueryIndex.empty(cacheSupplier);
        }
        otherKeysIdx.add(queries, i, value);
      }
    } else {
      matches.add(value);
    }
  }

  /**
   * Remove the specified value associated with a specific query from the index. Returns
   * true if a value was successfully removed.
   */
  public boolean remove(Query query, T value) {
    boolean result = false;
    for (Query q : query.dnfList()) {
      if (q == Query.TRUE) {
        result |= matches.remove(value);
      } else if (q == Query.FALSE) {
        break;
      } else {
        result |= remove(sort(q), 0, value);
      }
    }
    return result;
  }

  private boolean remove(List<Query.KeyQuery> queries, int i, T value) {
    boolean result = false;
    if (i < queries.size()) {
      Query.KeyQuery kq = queries.get(i);

      // Check for additional queries based on the same key and combine into a
      // composite if needed
      Query.CompositeKeyQuery composite = null;
      int j = i + 1;
      while (j < queries.size()) {
        Query.KeyQuery q = queries.get(j);
        if (kq.key().equals(q.key())) {
          if (composite == null) {
            composite = new Query.CompositeKeyQuery(kq);
            kq = composite;
          }
          composite.add(q);
          ++j;
        } else {
          break;
        }
      }

      if (key != null && key.equals(kq.key())) {
        if (kq instanceof Query.Equal) {
          String v = ((Query.Equal) kq).value();
          QueryIndex<T> idx = equalChecks.get(v);
          if (idx != null) {
            result |= idx.remove(queries, j, value);
            if (idx.isEmpty())
              equalChecks.remove(v);
          }
        } else if (kq instanceof Query.Has) {
          if (hasKeyIdx != null) {
            result |= hasKeyIdx.remove(queries, j, value);
            if (hasKeyIdx.isEmpty())
              hasKeyIdx = null;
          }
        } else {
          QueryIndex<T> idx = otherChecks.get(kq);
          if (idx != null && idx.remove(queries, j, value)) {
            result = true;
            if (idx.isEmpty()) {
              otherChecks.remove(kq);
              if (otherChecksTree.remove(kq)) {
                otherChecksCache.clear();
              }
            }
          }

          // Not queries should match if the key is missing from the id, so they need to
          // be included in the other keys sub-tree as well. Check this by seeing if it will
          // match an empty map as there could be a variety of inverted types.
          if (kq.matches(Collections.emptyMap()) && missingKeysIdx != null) {
            result |= missingKeysIdx.remove(queries, j, value);
            if (missingKeysIdx.isEmpty())
              missingKeysIdx = null;
          }
        }
      } else if (otherKeysIdx != null) {
        result |= otherKeysIdx.remove(queries, i, value);
        if (otherKeysIdx.isEmpty())
          otherKeysIdx = null;
      }
    } else {
      result |= matches.remove(value);
    }

    return result;
  }

  /**
   * Returns true if this index is empty and wouldn't match any ids.
   */
  public boolean isEmpty() {
    return matches.isEmpty()
        && equalChecks.values().stream().allMatch(QueryIndex::isEmpty)
        && otherChecks.values().stream().allMatch(QueryIndex::isEmpty)
        && isEmpty(hasKeyIdx)
        && isEmpty(otherKeysIdx)
        && isEmpty(missingKeysIdx);
  }

  private boolean isEmpty(QueryIndex<T> idx) {
    return idx == null || idx.isEmpty();
  }

  /**
   * Find all values where the corresponding queries match the specified id.
   *
   * @param id
   *     Id to check against the queries.
   * @return
   *     List of all matching values for the id.
   */
  public List<T> findMatches(Id id) {
    List<T> result = new ArrayList<>();
    forEachMatch(id, result::add);
    return result;
  }

  /**
   * Invoke the consumer for all values where the corresponding queries match the specified id.
   *
   * @param id
   *     Id to check against the queries.
   * @param consumer
   *     Function to invoke for values associated with a query that matches the id.
   */
  public void forEachMatch(Id id, Consumer<T> consumer) {
    forEachMatch(id, 0, new DedupConsumer<>(consumer));
  }

  @SuppressWarnings("PMD.NPathComplexity")
  private void forEachMatch(Id tags, int i, Consumer<T> consumer) {
    // Matches for this level
    matches.forEach(consumer);

    final String keyRef = key;
    if (keyRef != null) {

      boolean keyPresent = false;

      final int tagsSize = tags.size();
      for (int j = i; j < tagsSize; ++j) {
        String k = tags.getKey(j);
        String v = tags.getValue(j);
        int cmp = compare(k, keyRef);
        if (cmp == 0) {
          final int nextPos = j + 1;
          keyPresent = true;

          // Find exact matches
          QueryIndex<T> eqIdx = equalChecks.get(v);
          if (eqIdx != null) {
            eqIdx.forEachMatch(tags, nextPos, consumer);
          }

          // Scan for matches with other conditions
          List<QueryIndex<T>> otherMatches = otherChecksCache.get(v);
          if (otherMatches == null) {
            // Avoid the list and cache allocations if there are no other checks at
            // this level
            if (!otherChecks.isEmpty()) {
              List<QueryIndex<T>> tmp = new ArrayList<>();
              otherChecksTree.forEach(v, kq -> {
                if (kq instanceof Query.In || kq.matches(v)) {
                  QueryIndex<T> idx = otherChecks.get(kq);
                  if (idx != null) {
                    tmp.add(idx);
                    idx.forEachMatch(tags, nextPos, consumer);
                  }
                }
              });
              otherChecksCache.put(v, tmp);
            }
          } else {
            // Enhanced for loop typically results in iterator being allocated. Using
            // size/get avoids the allocation and has better throughput.
            final int n = otherMatches.size();
            for (int p = 0; p < n; ++p) {
              otherMatches.get(p).forEachMatch(tags, nextPos, consumer);
            }
          }

          // Check matches for has key
          final QueryIndex<T> hasKeyIdxRef = hasKeyIdx;
          if (hasKeyIdxRef != null) {
            hasKeyIdxRef.forEachMatch(tags, j, consumer);
          }
        }

        // Quit loop if the key was found or not present
        if (cmp >= 0) {
          break;
        }
      }

      // Check matches with other keys
      final QueryIndex<T> otherKeysIdxRef = otherKeysIdx;
      if (otherKeysIdxRef != null) {
        otherKeysIdxRef.forEachMatch(tags, i, consumer);
      }

      // Check matches with missing keys
      final QueryIndex<T> missingKeysIdxRef = missingKeysIdx;
      if (missingKeysIdxRef != null && !keyPresent) {
        missingKeysIdxRef.forEachMatch(tags, i, consumer);
      }
    }
  }

  /**
   * Find all values where the corresponding queries match the specified tags. This can be
   * used if the tags are not already structured as a spectator Id.
   *
   * @param tags
   *     Function to look up the value for a given tag key. The function should return
   *     {@code null} if there is no value for the key.
   * @return
   *     List of all matching values for the id.
   */
  public List<T> findMatches(Function<String, String> tags) {
    List<T> result = new ArrayList<>();
    forEachMatch(tags, result::add);
    return result;
  }

  /**
   * Invoke the consumer for all values where the corresponding queries match the specified tags.
   * This can be used if the tags are not already structured as a spectator Id.
   *
   * @param tags
   *     Function to look up the value for a given tag key. The function should return
   *     {@code null} if there is no value for the key.
   * @param consumer
   *     Function to invoke for values associated with a query that matches the id.
   */
  public void forEachMatch(Function<String, String> tags, Consumer<T> consumer) {
    forEachMatchImpl(tags, new DedupConsumer<>(consumer));
  }

  private void forEachMatchImpl(Function<String, String> tags, Consumer<T> consumer) {
    // Matches for this level
    matches.forEach(consumer);

    boolean keyPresent = false;
    final String keyRef = key;
    if (keyRef != null) {
      String v = tags.apply(keyRef);
      if (v != null) {
        keyPresent = true;

        // Find exact matches
        QueryIndex<T> eqIdx = equalChecks.get(v);
        if (eqIdx != null) {
          eqIdx.forEachMatch(tags, consumer);
        }

        // Scan for matches with other conditions
        List<QueryIndex<T>> otherMatches = otherChecksCache.get(v);
        if (otherMatches == null) {
          // Avoid the list and cache allocations if there are no other checks at
          // this level
          if (!otherChecks.isEmpty()) {
            List<QueryIndex<T>> tmp = new ArrayList<>();
            otherChecksTree.forEach(v, kq -> {
              if (kq instanceof Query.In || matches(kq, v)) {
                QueryIndex<T> idx = otherChecks.get(kq);
                if (idx != null) {
                  tmp.add(idx);
                  idx.forEachMatch(tags, consumer);
                }
              }
            });
            otherChecksCache.put(v, tmp);
          }
        } else {
          // Enhanced for loop typically results in iterator being allocated. Using
          // size/get avoids the allocation and has better throughput.
          final int n = otherMatches.size();
          for (int p = 0; p < n; ++p) {
            otherMatches.get(p).forEachMatch(tags, consumer);
          }
        }

        // Check matches for has key
        final QueryIndex<T> hasKeyIdxRef = hasKeyIdx;
        if (hasKeyIdxRef != null) {
          hasKeyIdxRef.forEachMatch(tags, consumer);
        }
      }
    }

    // Check matches with other keys
    final QueryIndex<T> otherKeysIdxRef = otherKeysIdx;
    if (otherKeysIdxRef != null) {
      otherKeysIdxRef.forEachMatch(tags, consumer);
    }

    // Check matches with missing keys
    final QueryIndex<T> missingKeysIdxRef = missingKeysIdx;
    if (missingKeysIdxRef != null && !keyPresent) {
      missingKeysIdxRef.forEachMatch(tags, consumer);
    }
  }

  /**
   * Check the set of tags, which could be a partial set, and return true if it is possible
   * that it would match some set of expressions. This method can be used as a cheap pre-filter
   * check. In some cases this can be useful to avoid expensive transforms to get the final
   * set of tags for matching.
   *
   * @param tags
   *     Partial set of tags to check against the index. Function is used to look up the
   *     value for a given tag key. The function should return {@code null} if there is no
   *     value for the key.
   * @return
   *     True if it is possible there would be a match based on the partial set of tags.
   */
  @SuppressWarnings("PMD.NPathComplexity")
  public boolean couldMatch(Function<String, String> tags) {
    // Matches for this level
    if (!matches.isEmpty()) {
      return true;
    }

    boolean keyPresent = false;
    final String keyRef = key;
    if (keyRef != null) {
      String v = tags.apply(keyRef);
      if (v != null) {
        keyPresent = true;

        // Check exact matches
        QueryIndex<T> eqIdx = equalChecks.get(v);
        if (eqIdx != null && eqIdx.couldMatch(tags)) {
          return true;
        }

        // Scan for matches with other conditions
        if (!otherChecks.isEmpty()) {
          boolean otherMatches = otherChecksTree.exists(v, kq -> {
            if (kq instanceof Query.In || couldMatch(kq, v)) {
              QueryIndex<T> idx = otherChecks.get(kq);
              return idx != null && idx.couldMatch(tags);
            }
            return false;
          });
          if (otherMatches) {
            return true;
          }
        }

        // Check matches for has key
        final QueryIndex<T> hasKeyIdxRef = hasKeyIdx;
        if (hasKeyIdxRef != null && hasKeyIdxRef.couldMatch(tags)) {
          return true;
        }
      }
    }

    // Check matches with other keys
    final QueryIndex<T> otherKeysIdxRef = otherKeysIdx;
    if (otherKeysIdxRef != null && otherKeysIdxRef.couldMatch(tags)) {
      return true;
    }

    // Check matches with missing keys
    return !keyPresent;
  }

  private boolean matches(Query.KeyQuery kq, String value) {
    if (kq instanceof Query.Regex) {
      Query.Regex re = (Query.Regex) kq;
      return re.pattern().matchesAfterPrefix(value);
    } else {
      return kq.matches(value);
    }
  }

  private boolean couldMatch(Query.KeyQuery kq, String value) {
    if (kq instanceof Query.Regex) {
      // For this possible matches prefix check is sufficient, avoid full regex to
      // keep the pre-filter checks cheap.
      return true;
    } else {
      return kq.matches(value);
    }
  }

  /**
   * Find hot spots in the index where there is a large set of linear matches, e.g. a bunch
   * of regex queries for a given key.
   *
   * @param threshold
   *     Threshold for the number of entries in the other checks sub-tree to be considered
   *     a hot spot.
   * @param consumer
   *     Function that will be invoked with a path and set of queries for the hot spot.
   */
  public void findHotSpots(int threshold, BiConsumer<List<String>, List<Query.KeyQuery>> consumer) {
    Deque<String> path = new ArrayDeque<>();
    findHotSpots(threshold, path, consumer);
  }

  private void findHotSpots(
      int threshold,
      Deque<String> path,
      BiConsumer<List<String>, List<Query.KeyQuery>> consumer
  ) {
    final String keyRef = key;
    if (keyRef != null) {
      path.addLast("K=" + keyRef);

      equalChecks.forEach((v, idx) -> {
        path.addLast(keyRef + "," + v + ",:eq");
        idx.findHotSpots(threshold, path, consumer);
        path.removeLast();
      });

      path.addLast("other-checks");
      if (otherChecks.size() > threshold) {
        List<Query.KeyQuery> queries = new ArrayList<>(otherChecks.keySet());
        consumer.accept(new ArrayList<>(path), queries);
      }
      otherChecks.forEach((q, idx) -> {
        path.addLast(q.toString());
        idx.findHotSpots(threshold, path, consumer);
        path.removeLast();
      });
      path.removeLast();

      final QueryIndex<T> hasKeyIdxRef = hasKeyIdx;
      if (hasKeyIdxRef != null) {
        path.addLast("has");
        hasKeyIdxRef.findHotSpots(threshold, path, consumer);
        path.removeLast();
      }

      path.removeLast();
    }

    final QueryIndex<T> otherKeysIdxRef = otherKeysIdx;
    if (otherKeysIdxRef != null) {
      path.addLast("other-keys");
      otherKeysIdxRef.findHotSpots(threshold, path, consumer);
      path.removeLast();
    }

    final QueryIndex<T> missingKeysIdxRef = missingKeysIdx;
    if (missingKeysIdxRef != null) {
      path.addLast("missing-keys");
      missingKeysIdxRef.findHotSpots(threshold, path, consumer);
      path.removeLast();
    }
  }

  @Override public String toString() {
    StringBuilder builder = new StringBuilder();
    buildString(builder, 0);
    return builder.toString();
  }

  private StringBuilder indent(StringBuilder builder, int n) {
    for (int i = 0; i < n * 4; ++i) {
      builder.append(' ');
    }
    return builder;
  }

  private void buildString(StringBuilder builder, int n) {
    final String keyRef = key;
    if (keyRef != null) {
      indent(builder, n).append("key: [").append(keyRef).append("]\n");
    }
    if (!equalChecks.isEmpty()) {
      indent(builder, n).append("equal checks:\n");
      equalChecks.forEach((v, idx) -> {
        indent(builder, n).append("- [").append(v).append("]\n");
        idx.buildString(builder, n + 1);
      });
    }
    if (!otherChecks.isEmpty()) {
      indent(builder, n).append("other checks:\n");
      otherChecks.forEach((kq, idx) -> {
        indent(builder, n).append("- [").append(kq).append("]\n");
        idx.buildString(builder, n + 1);
      });
    }
    final QueryIndex<T> hasKeyIdxRef = hasKeyIdx;
    if (hasKeyIdxRef != null) {
      indent(builder, n).append("has key:\n");
      hasKeyIdxRef.buildString(builder, n + 1);
    }
    final QueryIndex<T> otherKeysIdxRef = otherKeysIdx;
    if (otherKeysIdxRef != null) {
      indent(builder, n).append("other keys:\n");
      otherKeysIdxRef.buildString(builder, n + 1);
    }
    final QueryIndex<T> missingKeysIdxRef = missingKeysIdx;
    if (missingKeysIdxRef != null) {
      indent(builder, n).append("missing keys:\n");
      missingKeysIdxRef.buildString(builder, n + 1);
    }
    if (!matches.isEmpty()) {
      indent(builder, n).append("matches:\n");
      for (T value : matches) {
        indent(builder, n).append("- [").append(value).append("]\n");
      }
    }
  }
}
