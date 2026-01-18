/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.effect.kernel.{ Ref => CatsRef }
import cats.data.State

import fs2.Stream
import fs2.concurrent.SignallingRef

import core.{ Rxn, Ref, RefLike, RefLikeDefaults, AsyncReactive }

private final class Fs2SignallingRefWrapper[F[_], A](
  underlying: Ref[A],
  val pubSub: PubSub.Emit[A] & PubSub.Subscribe[A],
)(implicit F: AsyncReactive[F]) extends SignallingRef[F, A] {

  // Rxn API:

  final def refLike: RefLike[A] =
    _refLike

  private[this] val _refLike: RefLike[A] = new RefLikeDefaults[A] {

    final override def get: Rxn[A] =
      underlying.get

    final override def set(a: A): Rxn[Unit] = {
      underlying.set(a) *> notifyListeners(a)
    }

    final override def update(f: A => A): Rxn[Unit] = {
      underlying.updateAndGet(f).flatMap(notifyListeners)
    }

    final override def modify[C](f: A => (A, C)): Rxn[C] = {
      underlying.modify[(A, C)] { oldVal =>
        val tup: (A, C) = f(oldVal)
        (tup._1, tup)
      }.flatMap {
        case (newVal, c) =>
          notifyListeners(newVal).as(c)
      }
    }

    private[this] def notifyListeners(newVal: A): Rxn[Unit] = {
      pubSub.emit(newVal).map {
        case PubSub.Success =>
          () // ok
        case otherResult =>
          impossible(s"notifyListeners got ${otherResult}")
      }
    }
  }

  private[this] val _refLikeAsCats: CatsRef[F, A] =
    RefLike.catsRefFromRefLike[F, A](_refLike)

  // Streams:

  final override def continuous: Stream[F, A] =
    Stream.repeatEval(this.get)

  final override def discrete: Stream[F, A] =
    this.pubSub.subscribeWithInitial(PubSub.OverflowStrategy.unbounded, _refLike.get)

  // CatsRef:

  final override def get: F[A] =
    _refLikeAsCats.get

  final override def set(a: A): F[Unit] =
    _refLikeAsCats.set(a)

  override def access: F[(A, A => F[Boolean])] =
    _refLikeAsCats.access

  override def tryUpdate(f: A => A): F[Boolean] =
    _refLikeAsCats.tryUpdate(f)

  override def tryModify[B](f: A => (A, B)): F[Option[B]] =
    _refLikeAsCats.tryModify(f)

  override def update(f: A => A): F[Unit] =
    _refLikeAsCats.update(f)

  override def modify[B](f: A => (A, B)): F[B] =
    _refLikeAsCats.modify(f)

  override def tryModifyState[B](state: State[A, B]): F[Option[B]] =
    _refLikeAsCats.tryModifyState(state)

  override def modifyState[B](state: State[A, B]): F[B] =
    _refLikeAsCats.modifyState(state)
}

private object Fs2SignallingRefWrapper {

  final def apply[F[_] : AsyncReactive, A](
    initial: A,
    str: AllocationStrategy,
  ): Rxn[Fs2SignallingRefWrapper[F, A]] = {
    val ofStr = PubSub.OverflowStrategy.unbounded // TODO: make OverflowStrategy configurable
    (Ref[A](initial, str) * PubSub.simple[A](ofStr, str)).map {
      case (underlying, pubSub) =>
        new Fs2SignallingRefWrapper[F, A](underlying, pubSub)
    }
  }
}
