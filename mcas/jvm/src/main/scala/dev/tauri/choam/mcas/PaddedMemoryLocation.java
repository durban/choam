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

package dev.tauri.choam.mcas;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.ref.WeakReference;

final class PaddedMemoryLocation<A>
  extends PaddedMemoryLocationPadding
  implements MemoryLocation<A> {

  private static final VarHandle VALUE;
  private static final VarHandle VERSION;
  private static final VarHandle MARKER;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE = l.findVarHandle(PaddedMemoryLocation.class, "value", Object.class);
      VERSION = l.findVarHandle(PaddedMemoryLocation.class, "version", long.class);
      MARKER = l.findVarHandle(PaddedMemoryLocation.class, "marker", WeakReference.class);
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private final long _id0;
  private final long _id1;
  private final long _id2;
  private final long _id3;

  private volatile A value;

  private volatile long version = Version.Start;

  private volatile WeakReference<Object> marker; // = null

  PaddedMemoryLocation(A a, long i0, long i1, long i2, long i3) {
    this.value = a;
    this._id0 = i0;
    this._id1 = i1;
    this._id2 = i2;
    this._id3 = i3;
  }

  @Override
  public final long id0() {
    return this._id0;
  }

  @Override
  public final long id1() {
    return this._id1;
  }

  @Override
  public final long id2() {
    return this._id2;
  }

  @Override
  public final long id3() {
    return this._id3;
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

  public final long dummy() {
    return this.dummyImpl(42L);
  }
}
