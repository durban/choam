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
import dev.tauri.choam.React;

abstract class RefP1P1Base<A, B>
  extends RefId
  implements Ref2<A, B>, Ref2ImplBase<A, B> {

  private static final VarHandle VALUE_A;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE_A = l.findVarHandle(RefP1P1Base.class, "valueA", Object.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private volatile A valueA;

  private final Ref<A> refA = new Ref2Ref1<A, B>(this);

  RefP1P1Base(A a, long i0, long i1, long i2, long i3) {
    super(i0, i1, i2, i3);
    this.valueA = a;
  }

  RefP1P1Base(long i0, long i1, long i2, long i3) {
    super(i0, i1, i2, i3);
  }

  @Override
  public final Ref<A> _1() {
    return this.refA;
  }

  @Override
  public final A unsafeGetVolatile1() {
    return (A) VALUE_A.getVolatile(this);
  }

  @Override
  public final void unsafeSetVolatile1(A a) {
    VALUE_A.setVolatile(this, a);
  }

  @Override
  public final boolean unsafeCasVolatile1(A ov, A nv) {
    return VALUE_A.compareAndSet(this, ov, nv);
  }

  @Override
  public final A unsafeCmpxchgVolatile1(A ov, A nv) {
    return (A) VALUE_A.compareAndExchange(this, ov, nv);
  }
}

final class RefP1P1<A, B> extends Padding2<A, B> implements Ref2Impl<A, B> {

  private static final VarHandle VALUE_B;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE_B = l.findVarHandle(RefP1P1.class, "valueB", Object.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final long _id4;
  private final long _id5;
  private final long _id6;
  private final long _id7;

  private volatile B valueB;

  private final Ref<B> refB = new Ref2Ref2<A, B>(this);

  public RefP1P1(A a, B b, long i0, long i1, long i2, long i3, long i4, long i5, long i6, long i7) {
    super(a, i0, i1, i2, i3);
    this._id4 = i4;
    this._id5 = i5;
    this._id6 = i6;
    this._id7 = i7;
    this.valueB = b;
  }

  public RefP1P1(long i0, long i1, long i2, long i3, long i4, long i5, long i6, long i7) {
    super(i0, i1, i2, i3);
    this._id4 = i4;
    this._id5 = i5;
    this._id6 = i6;
    this._id7 = i7;
  }

  @Override
  public final Ref<B> _2() {
    return this.refB;
  }

  @Override
  public final B unsafeGetVolatile2() {
    return (B) VALUE_B.getVolatile(this);
  }

  @Override
  public final void unsafeSetVolatile2(B b) {
    VALUE_B.setVolatile(this, b);
  }

  @Override
  public final boolean unsafeCasVolatile2(B ov, B nv) {
    return VALUE_B.compareAndSet(this, ov, nv);
  }

  @Override
  public final B unsafeCmpxchgVolatile2(B ov, B nv) {
    return (B) VALUE_B.compareAndExchange(this, ov, nv);
  }

  @Override
  public final long id4() {
    return this._id4;
  }

  @Override
  public final long id5() {
    return this._id5;
  }

  @Override
  public final long id6() {
    return this._id6;
  }

  @Override
  public final long id7() {
    return this._id7;
  }

  @Override
  public final long dummyImpl1(long v) {
    return this.dummyImpl(v);
  }
}
