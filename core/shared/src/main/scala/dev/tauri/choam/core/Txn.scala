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
package core

import cats.kernel.Monoid
import cats.arrow.FunctionK
import cats.{ ~>, Applicative, Defer, StackSafeMonad }
import cats.effect.kernel.Unique

import internal.mcas.Mcas

// Note: not really private, published in dev.tauri.choam.stm
private[choam] sealed trait Txn[F[_], +B] {

  def map[C](f: B => C): Txn[F, C]

  def as[C](c: C): Txn[F, C]

  def void: Txn[F, Unit]

  def map2[C, D](that: Txn[F, C])(f: (B, C) => D): Txn[F, D]

  def productR[C](that: Txn[F, C]): Txn[F, C]

  def *> [C](that: Txn[F, C]): Txn[F, C]

  def productL[C](that: Txn[F, C]): Txn[F, B]

  def <* [C](that: Txn[F, C]): Txn[F, B]

  def product [C](that: Txn[F, C]): Txn[F, (B, C)]

  def flatMap[C](f: B => Txn[F, C]): Txn[F, C]

  def orElse[Y >: B](that: Txn[F, Y]): Txn[F, Y]

  private[core] def impl: Axn[B]

  def commit[X >: B](implicit F: Transactive[F]): F[X]
}

// Note: not really private, published in dev.tauri.choam.stm
private[choam] object Txn extends TxnInstances0 {

  final class Local[G[_], A] private[Txn] (private[this] var a: A) {
    final def get: Txn[G, A] = unsafe.delay { this.a }
    final def set(a: A): Txn[G, Unit] = unsafe.delay { this.a = a }
    final def update(f: A => A): Txn[G, Unit] = unsafe.delay { this.a = f(this.a) }
  }

  trait WithLocal[F[_], A, R] {
    def apply[G[_]]: (Local[G, A], Txn[F, *] ~> Txn[G, *]) => Txn[G, R]
  }

  final def withLocal[F[_], A, R](initial: A, body: WithLocal[F, A, R]): Txn[F, R] = {
    unsafe.delay {
      val local = new Local[F, A](initial)
      body[F].apply(local, FunctionK.id)
    }.flatMap { x => x }
  }

  private[core] trait UnsealedTxn[F[_], +B] extends Txn[F, B]

  final def pure[F[_], A](a: A): Txn[F, A] =
    Rxn.pure(a).castF[F]

  final def unit[F[_]]: Txn[F, Unit] =
    Rxn.unit[Any].castF[F]

  final def retry[F[_], A]: Txn[F, A] =
    Rxn.StmImpl.retryWhenChanged[A].castF[F]

  final def check[F[_]](cond: Boolean): Txn[F, Unit] =
    if (cond) unit else retry

  final def panic[F[_], A](ex: Throwable): Txn[F, A] =
    Rxn.panic(ex).castF[F]

  final def tailRecM[F[_], A, B](a: A)(f: A => Txn[F, Either[A, B]]): Txn[F, B] =
    Rxn.tailRecM(a)(f.asInstanceOf[Function1[A, Axn[Either[A, B]]]]).castF[F]

  final def defer[F[_], A](fa: => Txn[F, A]): Txn[F, A] =
    Axn.unsafe.suspend { fa.impl }.castF[F]

  final def unique[F[_]]: Txn[F, Unique.Token] =
    Rxn.unique.castF[F]

  private[choam] final object unsafe {

    private[choam] final def delay[F[_], A](uf: => A): Txn[F, A] =
      Axn.unsafe.delay[A](uf).castF[F]

    private[choam] final def delayContext[F[_], A](uf: Mcas.ThreadContext => A): Txn[F, A] =
      Rxn.unsafe.delayContext(uf).castF[F]

    /** Only for testing! */
    private[choam] final def retryUnconditionally[F[_], A]: Txn[F, A] =
      Rxn.unsafe.retry[A].castF[F]
  }
}

private[core] sealed abstract class TxnInstances0 extends TxnInstances1 { self: Txn.type =>

  import Rxn.Anything

  implicit final def monadInstance[F[_]]: StackSafeMonad[Txn[F, *]] =
    _monadInstance.asInstanceOf[StackSafeMonad[Txn[F, *]]]

  private[this] val _monadInstance: StackSafeMonad[Txn[Anything, *]] = new StackSafeMonad[Txn[Anything, *]] {
    final override def unit: Txn[Anything, Unit] =
      Txn.unit
    final override def pure[A](a: A): Txn[Anything, A] =
      Txn.pure(a)
    final override def point[A](a: A): Txn[Anything, A] =
      Txn.pure(a)
    final override def as[A, B](fa: Txn[Anything, A], b: B): Txn[Anything, B] =
      fa.as(b)
    final override def void[A](fa: Txn[Anything, A]): Txn[Anything, Unit] =
      fa.void
    final override def map[A, B](fa: Txn[Anything, A])(f: A => B): Txn[Anything, B] =
      fa.map(f)
    final override def map2[A, B, Z](fa: Txn[Anything, A], fb: Txn[Anything, B])(f: (A, B) => Z): Txn[Anything, Z] =
      fa.map2(fb)(f)
    final override def productR[A, B](fa: Txn[Anything, A])(fb: Txn[Anything, B]): Txn[Anything, B] =
      fa.productR(fb)
    final override def product[A, B](fa: Txn[Anything, A], fb: Txn[Anything, B]): Txn[Anything, (A, B)] =
      fa product fb
    final override def flatMap[A, B](fa: Txn[Anything, A])(f: A => Txn[Anything, B]): Txn[Anything, B] =
      fa.flatMap(f)
    final override def tailRecM[A, B](a: A)(f: A => Txn[Anything, Either[A, B]]): Txn[Anything, B] =
      Txn.tailRecM[Anything, A, B](a)(f)
  }

  implicit final def deferInstance[F[_]]: Defer[Txn[F, *]] =
    _deferInstance.asInstanceOf[Defer[Txn[F, *]]]

  private[this] val _deferInstance: Defer[Txn[Anything, *]] = new Defer[Txn[Anything, *]] {
    final override def defer[A](fa: => Txn[Anything, A]): Txn[Anything, A] =
      Txn.defer(fa)
    final override def fix[A](fn: Txn[Anything, A] => Txn[Anything, A]): Txn[Anything, A] = {
      // Note/TODO: see comment in Rxn.deferInstance
      val ref = new scala.runtime.ObjectRef[Txn[Anything, A]](null)
      ref.elem = fn(defer {
        self.acquireFence()
        ref.elem
      })
      self.releaseFence()
      ref.elem
    }
  }

  implicit final def uniqueInstance[F[_]]: Unique[Txn[F, *]] =
    _uniqueInstance.asInstanceOf[Unique[Txn[F, *]]]

  private[this] val _uniqueInstance: Unique[Txn[Anything, *]] = new Unique[Txn[Anything, *]] {
    final override def applicative: Applicative[Txn[Anything, *]] =
      self.monadInstance
    final override def unique: Txn[Anything, Unique.Token] =
      Txn.unique[Anything]
  }
}

private[core] sealed abstract class TxnInstances1 extends TxnCompanionPlatform { self: Txn.type =>

  implicit final def monoidInstance[F[_], B](implicit B: Monoid[B]): Monoid[Txn[F, B]] = new Monoid[Txn[F, B]] {
    final override def combine(x: Txn[F, B], y: Txn[F, B]): Txn[F, B] =
      x.map2(y) { (b1, b2) => B.combine(b1, b2) }
    final override def empty: Txn[F, B] =
      Txn.pure(B.empty)
  }
}
