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

package dev.tauri.choam.refs;

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

import dev.tauri.choam.internal.VarHandleHelper;
import dev.tauri.choam.internal.mcas.Version;

abstract class RefArrayBase extends RefIdOnly {

  private static final VarHandle VERSIONS;

  static {
    VERSIONS = VarHandleHelper.withInvokeExactBehavior(MethodHandles.arrayElementVarHandle(long[].class));
  }

  private final long[] versions;

  RefArrayBase(int size, long i0, long i1, long i2, long i3) {
    super(i0, i1, i2, i3);
    long[] arr = new long[size];
    for (int i = 0; i < size; i++) {
      arr[i] = Version.Start;
    }
    this.versions = arr;
  }

  protected long getVersionVolatile(int idx) {
    return (long) VERSIONS.get(this.versions, idx);
  }

  protected long cmpxchgVersionVolatile(int idx, long ov, long nv) {
    return (long) VERSIONS.compareAndExchange(this.versions, idx, ov, nv);
  }
}
