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

package dev.tauri.choam
package refs

import java.lang.ref.WeakReference

import internal.mcas.MemoryLocation

private final class Ref2Ref1[A, B](self: Ref2ImplBase[A, B])
  extends UnsealedRef[A]
  with MemoryLocation[A] {

  override def unsafeGetVolatile(): A =
    self.unsafeGetVolatile1()

  override def unsafeGetPlain(): A =
    self.unsafeGetPlain1()

  override def unsafeCasVolatile(ov: A, nv: A): Boolean =
    self.unsafeCasVolatile1(ov, nv)

  override def unsafeCmpxchgVolatile(ov: A, nv: A): A =
    self.unsafeCmpxchgVolatile1(ov, nv)

  override def unsafeSetVolatile(a: A): Unit =
    self.unsafeSetVolatile1(a)

  override def unsafeSetPlain(a: A): Unit =
    self.unsafeSetPlain1(a)

  final override def unsafeGetVersionVolatile(): Long =
    self.unsafeGetVersionVolatile1()

  final override def unsafeCmpxchgVersionVolatile(ov: Long, nv: Long): Long =
    self.unsafeCmpxchgVersionVolatile1(ov, nv)

  final override def unsafeGetMarkerVolatile(): WeakReference[AnyRef] =
    self.unsafeGetMarkerVolatile1()

  final override def unsafeCasMarkerVolatile(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    self.unsafeCasMarkerVolatile1(ov, nv)

  override def id: Long =
    self.id0

  final override def hashCode: Int =
    this.id.toInt

  final override def dummy(v: Byte): Long =
    self.dummyImpl1(v)
}

private final class Ref2Ref2[A, B](self: Ref2Impl[A, B])
  extends UnsealedRef[B]
  with MemoryLocation[B] {

  override def unsafeGetVolatile(): B =
    self.unsafeGetVolatile2()

  override def unsafeGetPlain(): B =
    self.unsafeGetPlain2()

  override def unsafeCasVolatile(ov: B, nv: B): Boolean =
    self.unsafeCasVolatile2(ov, nv)

  override def unsafeCmpxchgVolatile(ov: B, nv: B): B =
    self.unsafeCmpxchgVolatile2(ov, nv)

  override def unsafeSetVolatile(b: B): Unit =
    self.unsafeSetVolatile2(b)

  override def unsafeSetPlain(b: B): Unit =
    self.unsafeSetPlain2(b)

  final override def unsafeGetVersionVolatile(): Long =
    self.unsafeGetVersionVolatile2()

  final override def unsafeCmpxchgVersionVolatile(ov: Long, nv: Long): Long =
    self.unsafeCmpxchgVersionVolatile2(ov, nv)

  final override def unsafeGetMarkerVolatile(): WeakReference[AnyRef] =
    self.unsafeGetMarkerVolatile2()

  final override def unsafeCasMarkerVolatile(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean =
    self.unsafeCasMarkerVolatile2(ov, nv)

  override def id: Long =
    self.id1

  final override def hashCode: Int =
    this.id.toInt

  override def dummy(v: Byte): Long =
    self.dummyImpl2(v)
}
