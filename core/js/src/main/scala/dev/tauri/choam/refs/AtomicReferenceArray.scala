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

package dev.tauri.choam
package refs

/**
 * When compiled with Scala.js (see `CompatPlatform`), we
 * need an `AtomicReferenceArray` for `RefArray` (the one
 * in the Scala.js stdlib is not enough). This contains
 * exactly the methods we need.
 */
private final class AtomicReferenceArray[A](size: Int) {

  require(size >= 0)

  private[this] val arr: Array[AnyRef] =
    new Array[AnyRef](size)

  private[this] def box(a: A): AnyRef =
    a.asInstanceOf[AnyRef]

  final def get(i: Int): A =
    this.arr(i).asInstanceOf[A]

  final def getOpaque(i: Int): A =
    this.get(i)

  final def getPlain(i: Int): A =
    this.get(i)

  final def set(i: Int, newValue: A): Unit =
    this.arr(i) = box(newValue)

  final def setPlain(i: Int, newValue: A): Unit =
    this.set(i, newValue)

  final def compareAndSet(i: Int, expectedValue: A, newValue: A): Boolean = {
    val expBox = box(expectedValue)
    if (this.arr(i) eq expBox) {
      this.set(i, newValue)
      true
    } else {
      false
    }
  }

  final def compareAndExchange(i: Int, expectedValue: A, newValue: A): A = {
    val expBox = box(expectedValue)
    val old = this.arr(i)
    if (old eq expBox) {
      this.set(i, newValue)
    }
    old.asInstanceOf[A]
  }
}
