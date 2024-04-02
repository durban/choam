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

package dev.tauri.choam.internal.skiplist;

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

import dev.tauri.choam.internal.VarHandleHelper;

abstract class SkipListMapIndexBase<I extends SkipListMapIndexBase<I>> {

  private static final VarHandle RIGHT;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      RIGHT = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(SkipListMapIndexBase.class, "right", SkipListMapIndexBase.class));
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private I right;

  protected SkipListMapIndexBase(I r) {
    // plain writes are fine here, since
    // new `Index`es are alway published
    // with a volatile-CAS:
    this.right = r;
  }

  final I getRight() {
    return (I) RIGHT.getAcquire(this);
  }

  final void setRightP(I nv) {
    this.right = nv;
  }

  final boolean casRight(I ov, I nv) {
    return RIGHT.compareAndSet(this, ov, nv);
  }
}
