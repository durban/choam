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
import java.io.Serializable;

public final class EMCASWeakData<A>
  extends WeakReference<WordDescriptor<A>> {

  private static enum UninitializedValue { UNINITIALIZED }

  public static final Object UNINITIALIZED =
    UninitializedValue.UNINITIALIZED;

  private static final VarHandle VALUE;
  private static final VarHandle NEXT;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE = l.findVarHandle(EMCASWeakData.class, "_value", Object.class);
      NEXT = l.findVarHandle(EMCASWeakData.class, "_next", EMCASWeakData.class);
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private volatile A _value;

  private volatile EMCASWeakData<?> _next;

  EMCASWeakData(WordDescriptor<A> desc, EMCASWeakData<?> next) {
    super(desc);
    this.setValuePlain((A) UNINITIALIZED);
    this.setNextPlain(next);
  }

  EMCASWeakData(WordDescriptor<A> desc) {
    this(desc, null);
  }

  <B> EMCASWeakData<B> cast() {
    return (EMCASWeakData<B>) this;
  }

  A castToData() {
    return (A) this;
  }

  public A getValueVolatile() {
    return (A) VALUE.getVolatile(this);
  }

  void setValueVolatile(A a) {
    VALUE.setVolatile(this, a);
  }

  void setValuePlain(A a) {
    VALUE.set(this, a);
  }

  EMCASWeakData<?> getNextPlain() {
    return (EMCASWeakData<?>) NEXT.get(this);
  }

  private void setNextPlain(EMCASWeakData<?> n) {
    NEXT.set(this, n);
  }

  boolean canBeReplacedBad() {
    var next = this.getNextPlain();
    if (next != null) {
      if (next.canBeReplaced()) {
        this.setNextPlain(null);
        return this.get() == null;
      } else {
        return false;
      }
    } else {
      return this.get() == null;
    }
  }

  boolean canBeReplaced() {
    boolean canBeReplaced = true;
    EMCASWeakData<?> curr = this;
    while (canBeReplaced && (curr != null)) {
      if (curr.get() != null) {
        canBeReplaced = false;
      } else {
        curr = curr.getNextPlain();
      }
    }
    return canBeReplaced;
  }
}
