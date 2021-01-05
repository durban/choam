/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
package bench
package util

import scala.concurrent.stm._

class StmStack[A](els: Iterable[A]) {

  def this() =
    this(Iterable.empty)

  private[this] val head: Ref[TsList[A]] =
    Ref(TsList.End)

  atomic { implicit txn =>
    els.foreach(push)
  }

  def push(a: A)(implicit mt: MaybeTxn): Unit = atomic { implicit txn =>
    head.set(TsList.Cons(a, head.get))
  }

  def tryPop()(implicit mt: MaybeTxn): Option[A] = atomic { implicit txn =>
    head.get match {
      case TsList.End =>
        None
      case TsList.Cons(h, t) =>
        head.set(t)
        Some(h)
    }
  }

  private[bench] def unsafeToList()(implicit mt: MaybeTxn): List[A] = atomic { implicit txn =>
    head.get.toList
  }
}
