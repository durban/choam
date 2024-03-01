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

abstract class RefArrayBase<A> extends RefIdOnly {

  private static final VarHandle ARRAY;

  static {
    ARRAY = VarHandleHelper.withInvokeExactBehavior(MethodHandles.arrayElementVarHandle(Object[].class));
  }

  /*
   * `array` continously stores 3 things
   * for each index:
   * - a `RefArrayRef` (at i)
   * - the value/item itself, an `A` (at i + 1)
   * - a weak marker (at i + 2)
   *
   * `versions` stores the version
   * numbers (`long`s, at i)
   */

  private final Object[] array;
  protected final int _size;

  protected static final long[] initVersions(int size) {
    long[] vers = new long[size];
    for (int i = 0; i < size; i++) {
      vers[i] = Version.Start;
    }
    return vers;
  }

  protected RefArrayBase(int size, Object init, long i0, long i1, long i2, long i3, boolean sparse) {
    super(i0, i1, i2, i3);

    this._size = size;

    // init array:
    Object[] arr = new Object[3 * size];
    if (sparse) {
      for (int i = 0; i < size; i++) {
        int itemIdx = (3 * i) + 1;
        arr[itemIdx] = init;
        // we'll store `arr` into a final field,
        // so plain stores are enough here, these
        // writes will be visible to any reader
        // of `this`
      }
    } else {
      for (int i = 0; i < size; i++) {
        int refIdx = 3 * i;
        int itemIdx = refIdx + 1;
        arr[refIdx] = new RefArrayRef<A>(this, i);
        arr[itemIdx] = init;
        // we'll store `arr` into a final field,
        // so plain stores are enough here, these
        // writes will be visible to any reader
        // of `this`
      }
    }
    this.array = arr;
  }

  protected abstract long getVersionVolatile(int idx);

  protected abstract long cmpxchgVersionVolatile(int idx, long ov, long nv);

  protected Object getVolatile(int idx) {
    return ARRAY.getVolatile(this.array, idx);
  }

  protected Object getOpaque(int idx) {
    return ARRAY.getOpaque(this.array, idx);
  }

  protected Object getPlain(int idx) {
    return ARRAY.get(this.array, idx);
  }

  protected void setVolatile(int idx, Object nv) {
    ARRAY.setVolatile(this.array, idx, nv);
  }

  protected void setPlain(int idx, Object nv) {
    ARRAY.set(this.array, idx, nv);
  }

  protected boolean casVolatile(int idx, Object ov, Object nv) {
    return ARRAY.compareAndSet(this.array, idx, ov, nv);
  }

  protected Object cmpxchgVolatile(int idx, Object ov, Object nv) {
    return ARRAY.compareAndExchange(this.array, idx, ov, nv);
  }
}
