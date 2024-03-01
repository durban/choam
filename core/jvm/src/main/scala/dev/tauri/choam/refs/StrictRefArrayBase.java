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

abstract class StrictRefArrayBase<A> extends RefArrayBase<A> {

  private static final VarHandle VERSIONS_ARR;

  static {
    VERSIONS_ARR = VarHandleHelper.withInvokeExactBehavior(MethodHandles.arrayElementVarHandle(long[].class));
  }

  private final long[] versions;

  protected StrictRefArrayBase(int size, Object init, long i0, long i1, long i2, long i3) {
    super(size, init, i0, i1, i2, i3, false);
    this.versions = RefArrayBase.initVersions(size);
  }

  @Override
  protected final long getVersionVolatile(int idx) {
    return (long) VERSIONS_ARR.getVolatile(this.versions, idx);
  }

  @Override
  protected final long cmpxchgVersionVolatile(int idx, long ov, long nv) {
    return (long) VERSIONS_ARR.compareAndExchange(this.versions, idx, ov, nv);
  }
}
