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
package bench
package util

import cats.syntax.all._

import io.github.timwspence.cats.stm.{ STMLike }

object StmStackC {

  def make[S[F[_]] <: STMLike[F], F[_], A](q: StmStackCLike[S, F])(els: List[A]): q.stm.Txn[q.StmStackC[A]] = {
    import q._
    import q.stm._
    for {
      head <- TVar.of[TsList[A]](TsList.End)
      res = new StmStackC(head)
      _ <- els.traverse { item =>
        res.push(item)
      }
    } yield res
  }
}

object StmStackCLike {

  def apply[S[F[_]] <: STMLike[F], F[_]](s: S[F]): StmStackCLike[S, F] { val stm: s.type {} } =
    new StmStackCLike[S, F] { val stm: s.type = s }
}

abstract class StmStackCLike[S[F[_]] <: STMLike[F], F[_]]  {

  val stm: S[F]

  import stm._

  final class StmStackC[A](
    head: TVar[TsList[A]],
  ) {

    def push(a: A): Txn[Unit] = {
      head.modify { oldList =>
        TsList.Cons(a, oldList)
      }
    }

    def tryPop: Txn[Option[A]] = {
      head.get.flatMap {
        case TsList.End => pure(None)
        case TsList.Cons(h, t) => head.set(t).as(Some(h))
      }
    }
  }
}
