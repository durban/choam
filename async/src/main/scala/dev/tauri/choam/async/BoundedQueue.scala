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

abstract class BoundedQueue[F[_], A] {
  def tryEnqueue: A =#> Boolean
  def enqueue(a: A)(implicit F: AsyncReactive[F]): F[Unit]
  def tryDeque: Axn[Option[A]]
  def deque(implicit F: AsyncReactive[F]): F[A]
  def maxSize: Int
  private[choam] def currentSize: Axn[Int]
}

object BoundedQueue {

  def apply[F[_], A](maxSize: Int): Axn[BoundedQueue[F, A]] = {
    require(maxSize > 0)
    val _maxSize = maxSize
    (Queue[A] * Queue.withRemove[Promise[F, A]] * Queue.withRemove[(A, Promise[F, Unit])]).flatMap {
      case ((q, getters), setters) =>
        Ref[Int](0).map { s =>
          new BoundedQueue[F, A] {

            override def tryEnqueue: A =#> Boolean = {
              s.unsafeInvisibleRead.flatMap { size =>
                if (size < maxSize) s.unsafeCas(size, size + 1) *> q.enqueue.as(true)
                else s.unsafeCas(size, size).as(false)
              }
            }

            override def enqueue(a: A)(implicit F: AsyncReactive[F]): F[Unit] =
              F.monadCancel.bracket(this.setAcq(a))(this.setRel)(this.setUse)

            override def tryDeque: Axn[Option[A]] = {
              q.tryDeque.flatMap {
                case r @ Some(_) => s.update(_ - 1).as(r)
                case None => Rxn.pure(None)
              }
            }

            override def deque(implicit F: AsyncReactive[F]): F[A] =
              F.monadCancel.bracket(this.getAcq)(this.getUse)(this.getRel)

            override def maxSize: Int =
              _maxSize

            private[this] def getAcq(implicit F: AsyncReactive[F]): F[Either[Promise[F, A], A]] = {
              Promise[F, A].flatMap { p =>
                q.tryDeque.flatMap {
                  case Some(b) =>
                    // size was decremented...
                    setters.tryDeque.flatMap {
                      case Some((setterA, setterPromise)) =>
                        // ...then incremented
                        s.get *> q.enqueue.provide(setterA).flatTap(
                          setterPromise.complete.provide(()).void
                        ).as(Right(b))
                      case None =>
                        // ...then nothing
                        s.update(_ - 1).as(Right(b))
                    }
                  case None =>
                    // size didn't change
                    getters.enqueue.provide(p).as(Left(p))
                }
              }.run[F]
            }

            private[this] def getRel(r: Either[Promise[F, A], A])(implicit F: Reactive[F]): F[Unit] = r match {
              case Left(p) => getters.remove.void[F](p)
              case Right(_) => F.monad.unit
            }

            private[this] def getUse(r: Either[Promise[F, A], A])(implicit F: Reactive[F]): F[A] = r match {
              case Left(p) => p.get
              case Right(a) => F.monad.pure(a)
            }

            private[this] def setAcq(a: A)(implicit F: AsyncReactive[F]): F[Either[(A, Promise[F, Unit]), Unit]] = {
              Promise[F, Unit].flatMap { p =>
                getters.tryDeque.flatMap {
                  case Some(getterPromise) =>
                    getterPromise.complete.as(Right(()))
                  case None =>
                    this.tryEnqueue.flatMap {
                      case true =>
                        Rxn.pure(Right(()))
                      case false =>
                        setters.enqueue.contramap[A] { a => (a, p) }.as(Left((a, p)))
                    }
                }
              }.apply[F](a)
            }

            private[this] def setRel(r: Either[(A, Promise[F, Unit]), Unit])(implicit F: Reactive[F]): F[Unit] = r match {
              case Left(ap) => setters.remove.void[F](ap)
              case Right(_) => F.monad.unit
            }

            private[this] def setUse(r: Either[(A, Promise[F, Unit]), Unit])(implicit F: Reactive[F]): F[Unit] = r match {
              case Left(ap) => ap._2.get
              case Right(_) => F.monad.unit
            }

            private[choam] override def currentSize: Axn[Int] =
              s.get
          }
        }
    }
  }
}
