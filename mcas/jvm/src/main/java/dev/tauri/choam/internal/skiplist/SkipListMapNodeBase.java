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

package dev.tauri.choam.internal.skiplist;

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

import dev.tauri.choam.internal.VarHandleHelper;

abstract class SkipListMapNodeBase<V, N extends SkipListMapNodeBase<V, N>> {

  private static final VarHandle VALUE;
  private static final VarHandle NEXT;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(SkipListMapNodeBase.class, "value", Object.class));
      NEXT = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(SkipListMapNodeBase.class, "next", SkipListMapNodeBase.class));
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private V value;
  private N next;

  protected SkipListMapNodeBase(V v, N n) {
    // plain writes are fine here, since
    // new `Node`s are alway published
    // with a volatile-CAS:
    this.value = v;
    this.next = n;
  }

  final V getValue() {
    return (V) VALUE.getAcquire(this);
  }

  final boolean casValue(V ov, V nv) {
    return VALUE.compareAndSet(this, ov, nv);
  }

  final N getNext() {
    return (N) NEXT.getAcquire(this);
  }

  final boolean casNext(N ov, N nv) {
    return NEXT.compareAndSet(this, ov, nv);
  }
}
