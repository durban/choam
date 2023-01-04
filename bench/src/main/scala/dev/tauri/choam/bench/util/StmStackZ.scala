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
package bench
package util

import zio.IO
import zio.stm.{ STM, TRef }

object StmStackZ {

  sealed trait Elem[A]
  case class Node[A](data: A, next: TRef[Elem[A]]) extends Elem[A]
  case class End[A]() extends Elem[A]

  def apply[A](els: List[A]): IO[Nothing, StmStackZ[A]] = for {
    head <- TRef.makeCommit[TsList[A]](TsList.End)
    q = new StmStackZ[A](head)
    _ <- els.foldLeft[IO[Nothing, Unit]](zio.ZIO.unit) { (t, a) =>
      t.flatMap { _ => STM.atomically(q.push(a)) }
    }
  } yield q
}

final class StmStackZ[A](
  head: TRef[TsList[A]],
) {

  def push(a: A): STM[Nothing, Unit] = {
    head.modify { oldList =>
      ((), TsList.Cons(a, oldList))
    }
  }

  def tryPop: STM[Nothing, Option[A]] = {
    for {
      h <- head.get
      r <- h match {
        case TsList.Cons(h, t) => head.set(t).as(Some(h))
        case TsList.End => STM.succeed(None)
      }
    } yield r
  }
}
