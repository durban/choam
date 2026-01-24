/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import dev.tauri.choam.internal.VarHandleHelper;

public abstract class RefIdGenBase extends PaddedMemoryLocationPadding {

  /**
   * Constant for Fibonacci hashing
   *
   * See Knuth, Donald E. "The Art of Computer Programming"
   * vol. 3 "Sorting and Searching", section 6.4 "Hashing"
   * (page 518 in the 2nd edition)
   *
   * See also https://en.wikipedia.org/wiki/Hash_function#Fibonacci_hashing
   */
  public static final long GAMMA =
    0x9e3779b97f4a7c15L;

  private static final VarHandle CTR;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      CTR = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(RefIdGenBase.class, "ctr", long.class));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private volatile long ctr;

  protected RefIdGenBase(long startCtr) {
    this.ctr = startCtr;
  }

  final long getAndAddCtrO(long x) {
    // VarHandle doesn't have opaque FAA,
    // so we just approximate it with this:
    return (long) CTR.getAndAddAcquire(this, x);
  }

  protected final long getCtrV() {
    return this.ctr;
  }
}
