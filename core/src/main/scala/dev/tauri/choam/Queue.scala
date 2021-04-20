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

abstract class Queue[A] {
  def tryDeque: Axn[Option[A]]
  def enqueue: Rxn[A, Unit]
  private[choam] def unsafeToList[F[_]](implicit F: Reactive[F]): F[List[A]]
}

object Queue {

  abstract class WithRemove[A] extends Queue[A] {
    def remove: React[A, Boolean]
  }

  /**
   * Private, because `size` is hard to use correctly
   * (it cannot be composed with the other operations).
   */
  private[choam] abstract class WithSize[A] extends Queue[A] {
    def size: Axn[Int]
  }

  def apply[A]: Axn[Queue[A]] =
    MichaelScottQueue[A]

  def fromList[A](as: List[A]): Axn[Queue[A]] =
    MichaelScottQueue.fromList[A](as)

  def withRemove[A]: Axn[Queue.WithRemove[A]] =
    RemoveQueue[A]

  def withRemoveFromList[A](as: List[A]): Axn[Queue.WithRemove[A]] =
    RemoveQueue.fromList(as)

  private[choam] def withSize[A]: Axn[Queue.WithSize[A]] = {
    Queue[A].flatMap { q =>
      Ref[Int](0).map { s =>
        new WithSize[A] {

          final override def tryDeque: Axn[Option[A]] = {
            q.tryDeque.flatMap {
              case r @ Some(_) => s.update(_ - 1).as(r)
              case None => Axn.pure(None)
            }
          }

          final override def enqueue: Rxn[A, Unit] =
            s.update(_ + 1) *> q.enqueue

          private[choam] final override  def unsafeToList[F[_]](implicit F: Reactive[F]): F[List[A]] =
            q.unsafeToList[F]

          final override def size: Axn[Int] =
            s.get
        }
      }
    }
  }
}
