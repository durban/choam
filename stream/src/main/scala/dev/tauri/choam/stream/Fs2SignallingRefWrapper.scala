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
package stream

import cats.effect.Unique
import cats.data.State

import fs2.Stream
import fs2.concurrent.SignallingRef

import async.AsyncReactive

// TODO: also consider fs2.concurrent.Channel
private[stream] final class Fs2SignallingRefWrapper[F[_], A](
  self: Ref[A],
  @unused listeners: data.Map[Unique.Token, Int], // TODO
)(implicit F: AsyncReactive[F]) extends SignallingRef[F, A] {

  final override def continuous: Stream[F, A] =
    Stream.repeatEval(this.get)

  final override def discrete: Stream[F, A] =
    sys.error("TODO")

  override def get: F[A] =
    self.unsafeInvisibleRead.run[F]

  override def set(a: A): F[Unit] =
    sys.error("TODO")

  override def access: F[(A, A => F[Boolean])] =
    sys.error("TODO")

  override def tryUpdate(f: A => A): F[Boolean] =
    sys.error("TODO")

  override def tryModify[B](f: A => (A, B)): F[Option[B]] =
    sys.error("TODO")

  override def update(f: A => A): F[Unit] =
    sys.error("TODO")

  override def modify[B](f: A => (A, B)): F[B] =
    sys.error("TODO")

  override def tryModifyState[B](state: State[A,B]): F[Option[B]] =
    sys.error("TODO")

  override def modifyState[B](state: State[A,B]): F[B] =
    sys.error("TODO")
}
