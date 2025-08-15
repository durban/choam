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
package mcas
package emcas

import scala.scalanative.annotation.alwaysinline

private[emcas] abstract class EmcasWordDescBase[A](
  private[this] var _ov: A,
  private[this] var _nv: A,
) {

  protected def address: MemoryLocation[A]

  final def ov: A = {
    this._ov
  }

  final def nv: A = {
    this._nv
  }

  @alwaysinline
  private[this] final def atomicOv: AtomicHandle[A] = {
    AtomicHandle(this, "_ov")
  }

  @alwaysinline
  private[this] final def atomicNv: AtomicHandle[A] = {
    AtomicHandle(this, "_nv")
  }

  final def wasFinalized(wasSuccessful: Boolean, sentinel: A): Unit = {
    if (wasSuccessful) {
      atomicOv.setOpaque(sentinel)
      address.unsafeNotifyListeners()
    } else {
      atomicNv.setOpaque(sentinel)
    }
  }
}
