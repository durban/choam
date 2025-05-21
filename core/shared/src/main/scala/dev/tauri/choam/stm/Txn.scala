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
package stm

import cats.kernel.Monoid
import cats.{ ~>, Applicative, Defer, StackSafeMonad }
import cats.effect.kernel.Unique

import core.{ Rxn, Axn, RxnImpl }
import internal.mcas.Mcas

sealed trait Txn[+B] {

  def map[C](f: B => C): Txn[C]

  def as[C](c: C): Txn[C]

  def void: Txn[Unit]

  def map2[C, D](that: Txn[C])(f: (B, C) => D): Txn[D]

  def productR[C](that: Txn[C]): Txn[C]

  def *> [C](that: Txn[C]): Txn[C]

  def productL[C](that: Txn[C]): Txn[B]

  def <* [C](that: Txn[C]): Txn[B]

  def product [C](that: Txn[C]): Txn[(B, C)]

  def flatMap[C](f: B => Txn[C]): Txn[C]

  def orElse[Y >: B](that: Txn[Y]): Txn[Y]

  private[choam] def impl: RxnImpl[Any, B]

  def commit[F[_], X >: B](implicit F: Transactive[F]): F[X]
}

object Txn extends TxnInstances0 {

  private[choam] trait UnsealedTxn[+B] extends Txn[B]

  final def pure[A](a: A): Txn[A] =
    Rxn.pureImpl(a)

  final def unit: Txn[Unit] =
    Rxn.unitImpl[Any]

  final def retry[A]: Txn[A] =
    Rxn.StmImpl.retryWhenChanged[A]

  final def check(cond: Boolean): Txn[Unit] =
    if (cond) unit else retry

  final def panic[A](ex: Throwable): Txn[A] =
    Rxn.panicImpl(ex)

  final def tailRecM[A, B](a: A)(f: A => Txn[Either[A, B]]): Txn[B] =
    Rxn.tailRecMImpl(a)(f.asInstanceOf[Function1[A, Axn[Either[A, B]]]])

  final def defer[A](fa: => Txn[A]): Txn[A] =
    Axn.unsafe.suspendImpl { fa.impl }

  final def unique: Txn[Unique.Token] =
    Rxn.uniqueImpl

  private[choam] final object unsafe {

    trait WithLocal[A, R] {
      def apply[G[_]](local: TxnLocal[G, A], lift: Txn ~> G, inst: TxnLocal.Instances[G]): G[R]
    }

    final def withLocal[A, R](initial: A, body: WithLocal[A, R]): Txn[R] = {
      TxnLocal.withLocal(initial, body)
    }

    private[choam] final def delay[A](uf: => A): Txn[A] =
      Axn.unsafe.delayImpl[A](uf)

    private[choam] final def suspend[A](uf: => Txn[A]): Txn[A] =
      delay(uf).flatMap { x => x }

    private[choam] final def delayContext[A](uf: Mcas.ThreadContext => A): Txn[A] =
      Rxn.unsafe.delayContextImpl(uf)

    /** Only for testing! */
    private[choam] final def retryUnconditionally[A]: Txn[A] =
      Rxn.unsafe.retryImpl[A]

    /** Only for testing! */
    private[choam] final def plus[A](t1: Txn[A], t2: Txn[A]): Txn[A] = {
      t1.asInstanceOf[RxnImpl[Any, A]] + t2.asInstanceOf[RxnImpl[Any, A]]
    }
  }
}

private[stm] sealed abstract class TxnInstances0 extends TxnInstances1 { self: Txn.type =>

  implicit final def monadInstance: StackSafeMonad[Txn] =
    _monadInstance

  private[this] val _monadInstance: StackSafeMonad[Txn] = new StackSafeMonad[Txn] {
    final override def unit: Txn[Unit] =
      Txn.unit
    final override def pure[A](a: A): Txn[A] =
      Txn.pure(a)
    final override def point[A](a: A): Txn[A] =
      Txn.pure(a)
    final override def as[A, B](fa: Txn[A], b: B): Txn[B] =
      fa.as(b)
    final override def void[A](fa: Txn[A]): Txn[Unit] =
      fa.void
    final override def map[A, B](fa: Txn[A])(f: A => B): Txn[B] =
      fa.map(f)
    final override def map2[A, B, Z](fa: Txn[A], fb: Txn[B])(f: (A, B) => Z): Txn[Z] =
      fa.map2(fb)(f)
    final override def productR[A, B](fa: Txn[A])(fb: Txn[B]): Txn[B] =
      fa.productR(fb)
    final override def product[A, B](fa: Txn[A], fb: Txn[B]): Txn[(A, B)] =
      fa product fb
    final override def flatMap[A, B](fa: Txn[A])(f: A => Txn[B]): Txn[B] =
      fa.flatMap(f)
    final override def tailRecM[A, B](a: A)(f: A => Txn[Either[A, B]]): Txn[B] =
      Txn.tailRecM[A, B](a)(f)
  }

  implicit final def deferInstance: Defer[Txn] =
    _deferInstance

  private[this] val _deferInstance: Defer[Txn] = new Defer[Txn] {
    final override def defer[A](fa: => Txn[A]): Txn[A] =
      Txn.defer(fa)
    final override def fix[A](fn: Txn[A] => Txn[A]): Txn[A] = {
      // Note/TODO: see comment in Rxn.deferInstance
      val ref = new scala.runtime.ObjectRef[Txn[A]](null)
      ref.elem = fn(defer {
        self.acquireFence()
        ref.elem
      })
      self.releaseFence()
      ref.elem
    }
  }

  implicit final def uniqueInstance: Unique[Txn] =
    _uniqueInstance

  private[this] val _uniqueInstance: Unique[Txn] = new Unique[Txn] {
    final override def applicative: Applicative[Txn] =
      self.monadInstance
    final override def unique: Txn[Unique.Token] =
      Txn.unique
  }
}

private[stm] sealed abstract class TxnInstances1 extends TxnCompanionPlatform { self: Txn.type =>

  implicit final def monoidInstance[B](implicit B: Monoid[B]): Monoid[Txn[B]] = new Monoid[Txn[B]] {
    final override def combine(x: Txn[B], y: Txn[B]): Txn[B] =
      x.map2(y) { (b1, b2) => B.combine(b1, b2) }
    final override def empty: Txn[B] =
      Txn.pure(B.empty)
  }
}
