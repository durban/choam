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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;

import dev.tauri.choam.internal.VarHandleHelper;
import dev.tauri.choam.internal.mcas.Version;

final class RefP1P1<A, B> extends PaddingForP1P1<A, B> implements Ref2Impl<A, B> {

  private static final VarHandle VALUE_B;
  private static final VarHandle VERSION_B;
  private static final VarHandle MARKER_B;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE_B = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(RefP1P1.class, "valueB", Object.class));
      VERSION_B = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(RefP1P1.class, "versionB", long.class));
      MARKER_B = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(RefP1P1.class, "markerB", WeakReference.class));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final long _id1;

  private volatile B valueB;

  private volatile long versionB = Version.Start;

  private volatile WeakReference<Object> markerB; // = null

  private final Ref<B> refB = new Ref2Ref2<A, B>(this);

  public RefP1P1(A a, B b, long i0, long i1) {
    super(a, i0);
    this._id1 = i1;
    this.valueB = b;
  }

  public RefP1P1(long i0, long i1) {
    super(i0);
    this._id1 = i1;
  }

  @Override
  protected final String refToString() {
    return package$.MODULE$.refStringFrom8Ids(this.id0(), this.id1());
  }

  @Override
  public final Ref<B> _2() {
    return this.refB;
  }

  @Override
  public final B unsafeGetVolatile2() {
    return this.valueB;
  }

  @Override
  public final B unsafeGetPlain2() {
    return (B) VALUE_B.get(this);
  }

  @Override
  public final void unsafeSetVolatile2(B b) {
    this.valueB = b;
  }

  @Override
  public final void unsafeSetPlain2(B b) {
    VALUE_B.set(this, b);
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
  public final long unsafeGetVersionVolatile2() {
    return this.versionB;
  }

  @Override
  public final long unsafeCmpxchgVersionVolatile2(long ov, long nv) {
    return (long) VERSION_B.compareAndExchange(this, ov, nv);
  }

  @Override
  public final WeakReference<Object> unsafeGetMarkerVolatile2() {
    return this.markerB;
  }

  @Override
  public final boolean unsafeCasMarkerVolatile2(WeakReference<Object> ov, WeakReference<Object> nv) {
    return MARKER_B.compareAndSet(this, ov, nv);
  }

  @Override
  public final long id1() {
    return this._id1;
  }

  @Override
  public final long dummyImpl1(byte v) {
    return this.dummyImpl(v);
  }
}
