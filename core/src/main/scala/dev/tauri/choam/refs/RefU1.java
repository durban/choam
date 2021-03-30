/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import dev.tauri.choam.Ref;

public class RefU1<A> extends UnpaddedRefId implements Ref<A> {

  private static final VarHandle VALUE;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE = l.findVarHandle(RefU1.class, "value", Object.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private volatile A value;

  public RefU1(A a, long i0, long i1, long i2, long i3) {
    super(i0, i1, i2, i3);
    this.value = a;
  }

  public RefU1(long i0, long i1, long i2, long i3) {
    super(i0, i1, i2, i3);
  }

  @Override
  public final A unsafeGetVolatile() {
    return (A) VALUE.getVolatile(this);
  }

  @Override
  public final void unsafeSetVolatile(A a) {
    VALUE.setVolatile(this, a);
  }

  @Override
  public final boolean unsafeCasVolatile(A ov, A nv) {
    return VALUE.compareAndSet(this, ov, nv);
  }

  @Override
  public final A unsafeCmpxchgVolatile(A ov, A nv) {
    return (A) VALUE.compareAndExchange(this, ov, nv);
  }

  @Override
  public final long dummy(long v) {
    return 42L;
  }
}
