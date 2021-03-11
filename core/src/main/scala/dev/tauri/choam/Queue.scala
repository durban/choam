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
  def tryDeque: React[Unit, Option[A]]
  def enqueue: React[A, Unit]
  private[choam] def unsafeToList[F[_]](implicit F: Reactive[F]): F[List[A]]
}

object Queue {

  abstract class WithRemove[A] extends Queue[A] {
    def remove: React[A, Boolean]
  }

  def apply[A]: Action[Queue[A]] =
    MichaelScottQueue[A]

  def fromList[A](as: List[A]): Action[Queue[A]] =
    MichaelScottQueue.fromList[A](as)

  def withRemove[A]: Action[Queue.WithRemove[A]] =
    RemoveQueue[A]

  def withRemoveFromList[A](as: List[A]): Action[Queue.WithRemove[A]] =
    RemoveQueue.fromList(as)
}
