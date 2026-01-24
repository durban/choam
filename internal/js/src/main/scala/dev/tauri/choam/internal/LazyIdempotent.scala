/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

import LazyIdempotent.Initializer

// Note: this class is duplicated on JVM/JS
private[choam] final class LazyIdempotent[A] private (i: Initializer[A]) {

  private[this] var value: AnyRef =
    i

  final def get(): A = {
    val r = this.value match {
      case init: Initializer[_] =>
        val newA = init.mkNew()
        this.value = box(newA)
        newA
      case a =>
        a
    }

    r.asInstanceOf[A]
  }
}

private[choam] object LazyIdempotent {

  private final class Initializer[A](val mkNew: () => A)

  final def make[A](newA: => A): LazyIdempotent[A] = {
    makeFull(() => { newA }, null)
  }

  final def makeFull[A](newA: () => A, @unused cleanup: A => Unit): LazyIdempotent[A] = {
    new LazyIdempotent[A](new Initializer(newA))
  }
}
