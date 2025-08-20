/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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
package internal
package refs

import java.lang.ref.WeakReference

private trait Ref2ImplBase[A, B] {
  def unsafeGet1V(): A
  def unsafeGet1P(): A
  def unsafeCas1V(ov: A, nv: A): Boolean
  def unsafeCmpxchg1V(ov: A, nv: A): A
  def unsafeCmpxchg1R(ov: A, nv: A): A
  def unsafeSet1V(nv: A): Unit
  def unsafeSet1P(nv: A): Unit
  def unsafeGetVersion1V(): Long
  def unsafeCmpxchgVersion1V(ov: Long, nv: Long): Long
  def unsafeGetMarker1V(): WeakReference[AnyRef]
  def unsafeCasMarker1V(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean
  def unsafeCmpxchgMarker1R(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef]
  def id0(): Long
  def dummyImpl1(v: Byte): Long
}
