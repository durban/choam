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

import java.lang.ref.WeakReference;
import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

final class EMCASWeakData<A>
  extends WeakReference<EMCAS.WordDescriptor<A>> {

  private static final VarHandle VALUE;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE = l.findVarHandle(EMCASWeakData.class, "_value", Object.class);
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private volatile A _value;

  EMCASWeakData(EMCAS.WordDescriptor<A> desc) {
    super(desc);
  }

  <B> EMCASWeakData<B> cast() {
    return (EMCASWeakData<B>) this;
  }

  A getValueVolatile() {
    return (A) VALUE.getVolatile(this);
  }

  void setValueVolatile(A a) {
    VALUE.setVolatile(this, a);
  }

  void setValuePlain(A a) {
    VALUE.set(this, a);
  }
}
