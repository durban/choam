/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import dev.tauri.choam.mcas.Version;
import dev.tauri.choam.Ref;

abstract class RefP1P1Base<A, B>
  extends RefIdAndPadding
  implements Ref2<A, B>, Ref2ImplBase<A, B> {

  private static final VarHandle VALUE_A;
  private static final VarHandle VERSION_A;
  private static final VarHandle MARKER_A;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE_A = l.findVarHandle(RefP1P1Base.class, "valueA", Object.class);
      VERSION_A = l.findVarHandle(RefP1P1Base.class, "versionA", long.class);
      MARKER_A = l.findVarHandle(RefP1P1Base.class, "markerA", WeakReference.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private volatile A valueA;

  private volatile long versionA = Version.Start;

  private volatile WeakReference<Object> markerA; // = null

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
    return this.valueA;
  }

  @Override
  public final A unsafeGetPlain1() {
    return (A) VALUE_A.get(this);
  }

  @Override
  public final void unsafeSetVolatile1(A a) {
    this.valueA = a;
  }

  @Override
  public final void unsafeSetPlain1(A a) {
    VALUE_A.set(this, a);
  }

  @Override
  public final boolean unsafeCasVolatile1(A ov, A nv) {
    return VALUE_A.compareAndSet(this, ov, nv);
  }

  @Override
  public final A unsafeCmpxchgVolatile1(A ov, A nv) {
    return (A) VALUE_A.compareAndExchange(this, ov, nv);
  }

  @Override
  public final long unsafeGetVersionVolatile1() {
    return this.versionA;
  }

  @Override
  public final boolean unsafeCasVersionVolatile1(long ov, long nv) {
    return VERSION_A.compareAndSet(this, ov, nv);
  }

  @Override
  public final long unsafeCmpxchgVersionVolatile1(long ov, long nv) {
    return (long) VERSION_A.compareAndExchange(this, ov, nv);
  }

  @Override
  public final WeakReference<Object> unsafeGetMarkerVolatile1() {
    return this.markerA;
  }

  @Override
  public final boolean unsafeCasMarkerVolatile1(WeakReference<Object> ov, WeakReference<Object> nv) {
    return MARKER_A.compareAndSet(this, ov, nv);
  }
}
