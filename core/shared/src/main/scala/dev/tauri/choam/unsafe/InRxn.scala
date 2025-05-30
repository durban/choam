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
package unsafe

import internal.mcas.{ Mcas, MemoryLocation }

sealed trait InRxn extends MaybeInRxn {
  def initCtx(): Unit
  def rollback(): Unit
  def readRef[A](ref: MemoryLocation[A]): A
  def writeRef[A](ref: MemoryLocation[A], nv: A): Unit
  def imperativeTentativeRead[A](ref: MemoryLocation[A]): A
  def imperativeCommit(): Boolean
}

object InRxn {
  private[choam] trait UnsealedInRxn extends InRxn
}

sealed trait MaybeInRxn {
  def currentContext(): Mcas.ThreadContext
}

object MaybeInRxn {

  private[this] val _fallback: MaybeInRxn = new MaybeInRxn {
    final override def currentContext(): Mcas.ThreadContext =
      unsafe.unsafeRuntime.mcasImpl.currentContext()
  }

  implicit def fallback: MaybeInRxn = {
    _fallback
  }
}
