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

package dev.tauri.choam.ref;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import dev.tauri.choam.kcas.Ref;
import dev.tauri.choam.React;

abstract class Ref2Base<A> extends RefId {

  private static final VarHandle VALUE_A;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE_A = l.findVarHandle(Ref2Base.class, "valueA", Object.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private volatile A valueA;

  Ref2Base(A a, long i0, long i1, long i2, long i3) {
    super(i0, i1, i2, i3);
    this.valueA = a;
  }

  Ref2Base(long i0, long i1, long i2, long i3) {
    super(i0, i1, i2, i3);
  }

  public final A unsafeGet1() {
    return (A) VALUE_A.getVolatile(this);
  }

  public final void unsafeSet1(A a) {
    VALUE_A.setVolatile(this, a);
  }

  public final boolean unsafeTryPerformCas1(A ov, A nv) {
    return VALUE_A.compareAndSet(this, ov, nv);
  }
}

public class Ref2Impl<A, B> extends Padding2<A> implements Ref2<A, B> {

  private static final VarHandle VALUE_B;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE_B = l.findVarHandle(Ref2Impl.class, "valueB", Object.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final long _id4;
  private final long _id5;
  private final long _id6;
  private final long _id7;

  private volatile B valueB;

  public Ref2Impl(A a, B b, long i0, long i1, long i2, long i3, long i4, long i5, long i6, long i7) {
    super(a, i0, i1, i2, i3);
    this._id4 = i4;
    this._id5 = i5;
    this._id6 = i6;
    this._id7 = i7;
    this.valueB = b;
  }

  public Ref2Impl(long i0, long i1, long i2, long i3, long i4, long i5, long i6, long i7) {
    super(i0, i1, i2, i3);
    this._id4 = i4;
    this._id5 = i5;
    this._id6 = i6;
    this._id7 = i7;
  }

  @Override
  public final Ref<A> _1() {
    // TODO: cache
    return new Ref2Ref1(this);
  }

  @Override
  public final Ref<B> _2() {
    // TODO: cache
    return new Ref2Ref2(this);
  }

  public final B unsafeGet2() {
    return (B) VALUE_B.getVolatile(this);
  }

  public final void unsafeSet2(B b) {
    VALUE_B.setVolatile(this, b);
  }

  public final boolean unsafeTryPerformCas2(B ov, B nv) {
    return VALUE_B.compareAndSet(this, ov, nv);
  }

  public final long id4() {
    return this._id4;
  }

  public final long id5() {
    return this._id5;
  }

  public final long id6() {
    return this._id6;
  }

  public final long id7() {
    return this._id7;
  }
}
