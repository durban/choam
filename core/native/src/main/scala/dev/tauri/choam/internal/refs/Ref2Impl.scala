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

private trait Ref2Impl[A, B] {
  def unsafeGet2V(): B
  def unsafeGet2P(): B
  def unsafeCas2V(ov: B, nv: B): Boolean
  def unsafeCmpxchg2V(ov: B, nv: B): B
  def unsafeCmpxchg2R(ov: B, nv: B): B
  def unsafeSet2V(nv: B): Unit
  def unsafeSet2P(nv: B): Unit
  def unsafeGetVersion2V(): Long
  def unsafeCmpxchgVersion2V(ov: Long, nv: Long): Long
  def unsafeGetMarker2V(): WeakReference[AnyRef]
  def unsafeCasMarker2V(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): Boolean
  def unsafeCmpxchgMarker2R(ov: WeakReference[AnyRef], nv: WeakReference[AnyRef]): WeakReference[AnyRef]
  def id1(): Long
  def dummyImpl2(v: Byte): Long
}
