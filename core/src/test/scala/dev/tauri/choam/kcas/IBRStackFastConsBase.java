/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam.kcas;

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

abstract class IBRStackFastConsBase<A, T, M extends IBRManaged<T, M>>
  extends IBRManaged<T, M> {

  private static final VarHandle HEAD;
  private static final VarHandle TAIL;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      HEAD = l.findVarHandle(IBRStackFastConsBase.class, "_head", Object.class);
      TAIL = l.findVarHandle(IBRStackFastConsBase.class, "_tail", IBRManaged.class);
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @SuppressWarnings("unused")
  private A _head;

  @SuppressWarnings("unused")
  private M _tail;

  final void setHeadOpaque(A a) {
    HEAD.setOpaque(this, a);
  }

  final void setTailOpaque(M t) {
    TAIL.setOpaque(this, t);
  }

  VarHandle getHeadVh() {
    return HEAD;
  }

  VarHandle getTailVh() {
    return TAIL;
  }
}
