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
package async

import cats.implicits._
import cats.effect.{ ConcurrentEffect, IO, ContextShift }

import kcas.KCAS
import Promise._

final class Promise[A] private (ref: kcas.Ref[State[A]]) {

  val tryComplete: React[A, Boolean] = React.computed { a =>
    ref.invisibleRead.flatMap {
      case w @ Waiting(cbs) =>
        ref.cas(w, Done(a)).rmap(_ => true).postCommit(React.delay { _ =>
          cbs.valuesIterator.foreach(_(a))
        })
      case Done(_) =>
        React.ret(false)
    }
  }

  def get[F[_]](
    implicit
    F: ConcurrentEffect[F],
    cs: ContextShift[F],
    kcas: KCAS
  ): F[A] = {
    ref.invisibleRead.run[F].flatMap {
      case Waiting(_) =>
        F.delay(new Id).flatMap { id =>
          val tsk = IO.cancelable[A] { cb =>
            insertCallback(id, cb).unsafePerform(()) match {
              case None =>
                  ()
              case Some(a) =>
                cb(Right(a))
            }
            ref.modify {
              case Waiting(cbs) =>
                Waiting(cbs - id)
              case d @ Done(_) =>
                d
            }.discard.run[IO]
          }.flatTap(_ => IO.cancelBoundary)
          F.liftIO(tsk)
        }
      case Done(a) =>
        F.pure(a)
    }
  }

  private def insertCallback[F[_]](id: Id, cb: Either[Throwable, A] => Unit)(
    implicit
    F: ConcurrentEffect[F],
    cs: ContextShift[F]
  ): React[Unit, Option[A]] = {
    val kv = (id, { a: A =>
      F.runAsync(cs.shift *> F.delay {
        cb(Right(a))
      }) { IO.pure(_).rethrow }.unsafeRunSync()
    })
    ref.modify {
      case Waiting(cbs) => Waiting(cbs + kv)
      case d @ Done(_) => d
    }.map {
      case Waiting(_) => None
      case Done(a) => Some(a)
    }
  }
}

object Promise {

  private final class Id

  private sealed abstract class State[A]
  private final case class Waiting[A](cbs: Map[Id, A => Unit]) extends State[A]
  private final case class Done[A](a: A) extends State[A]

  def apply[A]: React[Unit, Promise[A]] =
    React.delay(_ => new Promise[A](kcas.Ref.mk(Waiting(Map.empty))))
}
