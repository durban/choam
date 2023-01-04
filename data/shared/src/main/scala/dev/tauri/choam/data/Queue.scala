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
package data

import cats.Monad
import cats.syntax.all._

trait QueueSource[+A] {

  def tryDeque: Axn[Option[A]]

  private[choam] final def drainOnce[F[_], AA >: A](implicit F: Reactive[F]): F[List[AA]] = {
    F.monad.tailRecM(List.empty[AA]) { acc =>
      F.monad.map(F.run(this.tryDeque)) {
        case Some(a) => Left(a :: acc)
        case None => Right(acc.reverse)
      }
    }
  }
}

trait QueueSink[-A] {
  def tryEnqueue: Rxn[A, Boolean]
}

trait QueueSourceSink[A]
  extends QueueSource[A]
  with  QueueSink[A]

trait UnboundedQueueSink[-A] extends QueueSink[A] {
  def enqueue: Rxn[A, Unit]
}

trait Queue[A]
  extends QueueSourceSink[A]
  with UnboundedQueueSink[A]

object Queue {

  type Remover =
    Axn[Unit]

  abstract class WithRemove[A] extends Queue[A] {
    def enqueueWithRemover: Rxn[A, Remover]
  }

  trait WithSize[A] extends Queue[A] {
    def size: Axn[Int]
  }

  def unbounded[A]: Axn[Queue[A]] =
    MsQueue[A]

  def unboundedWithRemove[A]: Axn[Queue.WithRemove[A]] =
    RemoveQueue.apply[A]

  def bounded[A](bound: Int): Axn[QueueSourceSink[A]] =
    dropping(bound)

  def dropping[A](capacity: Int): Axn[Queue.WithSize[A]] =
    DroppingQueue.apply[A](capacity)

  def ringBuffer[A](capacity: Int): Axn[Queue.WithSize[A]] =
    RingBuffer.apply[A](capacity)

  // TODO: do we need this?
  def lazyRingBuffer[A](capacity: Int): Axn[Queue.WithSize[A]] =
    RingBuffer.lazyRingBuffer[A](capacity)

  def unboundedWithSize[A]: Axn[Queue.WithSize[A]] = {
    Queue.unbounded[A].flatMapF { q =>
      Ref[Int](0).map { s =>
        new WithSize[A] {

          final override def tryDeque: Axn[Option[A]] = {
            q.tryDeque.flatMapF {
              case r @ Some(_) => s.update(_ - 1).as(r)
              case None => Rxn.pure(None)
            }
          }

          final override def enqueue: Rxn[A, Unit] =
            s.update(_ + 1) *> q.enqueue

          final override def tryEnqueue: Rxn[A, Boolean] =
            this.enqueue.as(true)

          final override def size: Axn[Int] =
            s.get
        }
      }
    }
  }

  private[data] def unpadded[A]: Axn[Queue[A]] =
    MsQueue.unpadded[A]

  private[data] def fromList[F[_] : Reactive, Q[a] <: Queue[a], A](mkEmpty: Axn[Q[A]])(as: List[A]): F[Q[A]] = {
    implicit val m: Monad[F] = Reactive[F].monad
    mkEmpty.run[F].flatMap { q =>
      as.traverse(a => q.enqueue[F](a)).as(q)
    }
  }
}
