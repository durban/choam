/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import dev.tauri.choam.vhandle.VarHandleHelper;
import dev.tauri.choam.mcas.MemoryLocation;
import dev.tauri.choam.mcas.Version;

final class RefP1<A>
  extends RefIdAndPadding
  implements Ref<A>, MemoryLocation<A> {

  private static final VarHandle VALUE;
  private static final VarHandle VERSION;
  private static final VarHandle MARKER;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(RefP1.class, "value", Object.class));
      VERSION = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(RefP1.class, "version", long.class));
      MARKER = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(RefP1.class, "marker", WeakReference.class));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private volatile A value;

  private volatile long version = Version.Start;

  private volatile WeakReference<Object> marker; // = null

  public RefP1(A a, long i0, long i1, long i2, long i3) {
    super(i0, i1, i2, i3);
    this.value = a;
  }

  /** Only for benchmarks */
  public RefP1(A a, long i0, long i1, long i2, long i3, String release) {
    super(i0, i1, i2, i3);
    VALUE.setRelease(this, a);
  }

  public RefP1(long i0, long i1, long i2, long i3) {
    super(i0, i1, i2, i3);
  }

  @Override
  protected final String refToString() {
    return package$.MODULE$.refStringFrom4Ids(this.id0(), this.id1(), this.id2(), this.id3());
  }

  @Override
  public final A unsafeGetVolatile() {
    return this.value;
  }

  @Override
  public final A unsafeGetPlain() {
    return (A) VALUE.get(this);
  }

  @Override
  public final void unsafeSetVolatile(A a) {
    this.value = a;
  }

  @Override
  public final void unsafeSetPlain(A a) {
    VALUE.set(this, a);
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
  public final long unsafeGetVersionVolatile() {
    return this.version;
  }

  @Override
  public final boolean unsafeCasVersionVolatile(long ov, long nv) {
    return VERSION.compareAndSet(this, ov, nv);
  }

  @Override
  public final long unsafeCmpxchgVersionVolatile(long ov, long nv) {
    return (long) VERSION.compareAndExchange(this, ov, nv);
  }

  @Override
  public final WeakReference<Object> unsafeGetMarkerVolatile() {
    return this.marker;
  }

  @Override
  public final boolean unsafeCasMarkerVolatile(WeakReference<Object> ov, WeakReference<Object> nv) {
    return MARKER.compareAndSet(this, ov, nv);
  }

  @Override
  public final long dummy(long v) {
    return this.dummyImpl(v);
  }
}
