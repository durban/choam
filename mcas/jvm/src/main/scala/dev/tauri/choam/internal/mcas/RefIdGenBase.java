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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import dev.tauri.choam.internal.VarHandleHelper;

public abstract class RefIdGenBase extends PaddedMemoryLocationPadding {

  public static final long GAMMA =
    0x9e3779b97f4a7c15L; // Fibonacci hashing: https://web.archive.org/web/20161121124236/http://brpreiss.com/books/opus4/html/page214.html

  private static final VarHandle CTR;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      CTR = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(RefIdGenBase.class, "ctr", long.class));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private volatile long ctr =
    Long.MIN_VALUE; // TODO: start from something more "random"

  final long getAndAddCtrO(long x) {
    // VarHandle doesn't have opaque FAA,
    // so we just approximate it with this:
    return (long) CTR.getAndAddAcquire(this, x);
  }
}
