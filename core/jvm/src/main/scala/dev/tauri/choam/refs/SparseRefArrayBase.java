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

abstract class SparseRefArrayBase<A> extends RefArrayBase<A> {

  private static final VarHandle VERSIONS;
  private static final VarHandle VERSIONS_ARR;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VERSIONS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(SparseRefArrayBase.class, "versions", long[].class));
      VERSIONS_ARR = VarHandleHelper.withInvokeExactBehavior(MethodHandles.arrayElementVarHandle(long[].class));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private volatile long[] versions; // = null

  protected SparseRefArrayBase(int size, Object init, long idBase) {
    super(size, init, idBase, true);
  }

  @Override
  protected final long getVersionVolatile(int idx) {
    long[] vers = (long[]) VERSIONS.getAcquire(this);
    if (vers == null) {
      // FIXME: in this case, we only had a `getAcquire`, so we're technically NOT `getVersionVolatile`
      return Version.Start;
    } else {
      return (long) VERSIONS_ARR.getVolatile(vers, idx);
    }
  }

  @Override
  protected final long cmpxchgVersionVolatile(int idx, long ov, long nv) {
    return (long) VERSIONS_ARR.compareAndExchange(this.getOrInitVersions(), idx, ov, nv);
  }

  private final long[] getOrInitVersions() {
    long[] vers = (long[]) VERSIONS.getAcquire(this);
    if (vers == null) {
      vers = RefArrayBase.initVersions(this._size);
      long[] wit = (long[]) VERSIONS.compareAndExchangeRelease(this, (long[]) null, vers);
      if (wit == null) {
        return vers;
      } else {
        // what we want here is a cmpxchg which has
        // Release semantics on success, and Acquire
        // semantics on failure (to get the witness);
        // `VarHandle` has no method for that, so we
        // approximate it with the compareAndExchangeRelease
        // above, and a fence here:
        VarHandle.acquireFence();
        return wit;
      }
    } else {
      return vers;
    }
  }
}
