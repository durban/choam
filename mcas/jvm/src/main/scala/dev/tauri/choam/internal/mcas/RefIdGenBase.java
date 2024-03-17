/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

public abstract class RefIdGenBase {

  public static final long GAMMA =
    0x9e3779b97f4a7c15L; // Fibonacci hashing: https://web.archive.org/web/20161121124236/http://brpreiss.com/books/opus4/html/page214.html

  /**
   * Next power of 2 which is `>= x`.
   *
   * `clp2` from Hacker's Delight by Henry S. Warren, Jr. (section 3–2).
   */
  static final int nextPowerOf2(int x) {
    assert (x > 0) && (x <= (1 << 30));
    return 0x80000000 >>> (Integer.numberOfLeadingZeros(x - 1) - 1);
  }
}
