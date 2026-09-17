/*
 * Copyright 2014-2026 Netflix, Inc.
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
package com.netflix.spectator.perf;

import com.netflix.spectator.impl.AsciiSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Optional;

/**
 * Measures {@link AsciiSet} on the inputs that matter in practice: {@code containsAll} and
 * {@code replaceNonMembers} cover the meter lookup path, the rest cover pattern compilation.
 *
 * <p>To A/B a change here, build two jmh jars from two git states of {@code AsciiSet.java} and
 * run this benchmark against each, rather than reproducing an old implementation in this file:
 * which class a method compiles as part of can shift its measured throughput more than the
 * algorithm difference being isolated.</p>
 */
@State(Scope.Thread)
public class AsciiSetBench {

  /** The character set the Atlas registry applies to every tag key and value. */
  private static final String VALID = "-._A-Za-z0-9~^";

  private final AsciiSet set = AsciiSet.fromPattern(VALID);
  private final AsciiSet other = AsciiSet.fromPattern("a-z");
  private final AsciiSet setCopy = AsciiSet.fromPattern(VALID);

  // Built in setup, not assigned from literals inline, to avoid constant folding.

  /** Typical tag value: short and already valid. */
  private String shortClean;

  /** Typical metric name: longer, still valid. */
  private String longClean;

  /** A value that needs fixing, with the first bad character near the end. */
  private String dirtyLate;

  /** A value that needs fixing from the very first character. */
  private String dirtyEarly;

  /** Text to build a set from, kept separate from {@code VALID} for the same reason as above. */
  private String patternText;

  @Setup
  public void setup() {
    shortClean = new String("us-east-1c".toCharArray());
    longClean = new String("ipc.server.call.duration.percentile".toCharArray());
    dirtyLate = new String("deploy.prod.us-east-1/cluster".toCharArray());
    dirtyEarly = new String("/api/v1/users?id=42&name=bob".toCharArray());
    patternText = new String(VALID.toCharArray());
  }

  @Benchmark public AsciiSet fromPattern() {
    return AsciiSet.fromPattern(patternText);
  }

  // containsAll on already valid input: the dominant case on the meter lookup path.

  @Benchmark public boolean shortClean() {
    return set.containsAll(shortClean);
  }

  @Benchmark public boolean longClean() {
    return set.containsAll(longClean);
  }

  // containsAll that fails, so the scan exits early.

  @Benchmark public boolean dirty() {
    return set.containsAll(dirtyLate);
  }

  // replaceNonMembers: the allocating path.

  @Benchmark public String replaceClean() {
    return set.replaceNonMembers(longClean, '_');
  }

  @Benchmark public String replaceLate() {
    return set.replaceNonMembers(dirtyLate, '_');
  }

  @Benchmark public String replaceEarly() {
    return set.replaceNonMembers(dirtyEarly, '_');
  }

  // Single character membership, as used by the pattern matcher.

  @Benchmark public void contains(Blackhole bh) {
    bh.consume(set.contains('a'));
    bh.consume(set.contains('/'));
    bh.consume(set.contains('é'));
  }

  // Set algebra and identity, as used while compiling a pattern.

  @Benchmark public AsciiSet union() {
    return set.union(other);
  }

  @Benchmark public boolean equalsSet() {
    return set.equals(setCopy);
  }

  @Benchmark public boolean isEmpty() {
    return set.isEmpty();
  }

  @Benchmark public Optional<Character> character() {
    return set.character();
  }
}
