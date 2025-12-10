/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam.internal.mcas;

// Note: this class/object is duplicated for JVM/JS
public final class Consts {

  public static final long OPTIMISTIC =
    1L;

  public static final long PESSIMISTIC =
    0L;

  public static final long InvalidListenerId =
    Long.MIN_VALUE;

  public static final String statsEnabledProp =
    "dev.tauri.choam.stats";

  // Note: this is configured to be false
  // unconditionally in native-image.properties
  public static final boolean statsEnabled =
    Boolean.getBoolean(statsEnabledProp);

  public static final String mcasImplProp =
    "dev.tauri.choam.internal.mcas.impl";

  // Note: this is configured to be ""
  // unconditionally in native-image.properties
  public static final String mcasImpl =
    System.getProperty(mcasImplProp);

  /**
   * Next power of 2 which is `>= x`.
   *
   * `clp2` from Hacker's Delight by Henry S. Warren, Jr. (section 3–2).
   */
  public static final int nextPowerOf2(int x) {
    // assert (x > 0) && (x <= (1 << 30));
    return 0x80000000 >>> (Integer.numberOfLeadingZeros(x - 1) - 1);
  }

  /** https://zimbry.blogspot.com/2011/09/better-bit-mixing-improving-on.html */
  public static final long staffordMix13(long s) {
    long n = s;
    n ^= (n >>> 30);
    n *= 0xbf58476d1ce4e5b9L;
    n ^= (n >>> 27);
    n *= 0x94d049bb133111ebL;
    n ^= (n >>> 31);
    return n;
  }
}
