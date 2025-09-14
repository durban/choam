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

import java.util.UUID

import cats.kernel.Monoid
import cats.{ ~>, Applicative, Defer, StackSafeMonad }
import cats.effect.kernel.Unique
import cats.effect.std.UUIDGen

import core.{ Rxn, RxnImpl }
import internal.mcas.Mcas

sealed trait Txn[+A] {

  /**
   * Performs `this`, then applies
   * `f` to its result.
   *
   * @see [[cats.Functor.map]]
   */
  def map[B](f: A => B): Txn[B]

  /**
   * Equivalent to `.map(_ => b)`.
   *
   * @see [[cats.Functor.as]]
   */
  def as[B](b: B): Txn[B]

  /**
   * Equivalent to `.map(_ => ())`.
   *
   * @see [[cats.Functor.void]]
   */
  def void: Txn[Unit]

  /**
   * Performs `this`, then `that`, then
   * applies `f` to the 2 results.
   *
   * @see [[cats.Apply.map2]]
   */
  def map2[B, C](that: Txn[B])(f: (A, B) => C): Txn[C]

  /**
   * Performs `this`, then `that`; the
   * result will be the result of `that`.
   *
   * @see [[cats.Apply.productR]]
   */
  def productR[B](that: Txn[B]): Txn[B]

  /**
   * Equivalent to `productR`.
   *
   * @see [[cats.Apply.productR]]
   */
  def *> [B](that: Txn[B]): Txn[B]

  /**
   * Performs `this`, then `that`; the
   * result will be the result of `this`.
   *
   * @see [[cats.Apply.productL]]
   */
  def productL[B](that: Txn[B]): Txn[A]

  /**
   * Equivalent to `productL`.
   *
   * @see [[cats.Apply.productL]]
   */
  def <* [B](that: Txn[B]): Txn[A]

  /**
   * Performs `this`, then `that`; the
   * result will be a tuple of both
   * results.
   *
   * @see [[cats.Apply.product]]
   */
  def product[B](that: Txn[B]): Txn[(A, B)]

  /**
   * Performs `this`, then applies `f`
   * to its result, then performs the
   * result of `f`.
   *
   * @see [[cats.FlatMap.flatMap]]
   */
  def flatMap[B](f: A => Txn[B]): Txn[B]

  /**
   * Equivalent to `.flatMap(a => a)`.
   *
   * @see [[cats.FlatMap.flatten]]
   */
  def flatten[B](implicit ev: A <:< Txn[B]): Txn[B]

  /**
   * Creates a `Txn` with 2 alternatives: `this`
   * (left side) and `that` (right side).
   *
   * Thus, `a orElse b` is a `Txn` which: if
   * `a` succeeds, succeeds with the result of
   * `a`; otherwise, it performs `b`.
   *
   * @see [[Txn.retry]]
   */
  def orElse[X >: A](that: Txn[X]): Txn[X]

  private[choam] def impl: RxnImpl[A]
}

object Txn extends TxnInstances0 {

  private[choam] trait UnsealedTxn[+B] extends Txn[B]

  /**
   * Succeeds with `a`.
   *
   * @see [[cats.Applicative#pure]]
   */
  final def pure[A](a: A): Txn[A] =
    Rxn.pureImpl(a)

  /**
   * Equivalent to `pure(())`.
   *
   * @see [[cats.Applicative#unit]]
   */
  final def unit: Txn[Unit] =
    Rxn.unitImpl

  private[choam] final def _true: Txn[Boolean] =
    Rxn.trueImpl

  private[choam] final def _false: Txn[Boolean] =
    Rxn.falseImpl

  private[choam] final def none[A]: Txn[Option[A]] =
    Rxn.noneImpl

  /**
   * Retries the current `Txn`.
   *
   * @note It is an error to retry if the read-set is empty.
   *
   * @see [[Txn#orElse]]
   */
  final def retry[A]: Txn[A] =
    Rxn.StmImpl.retryWhenChanged[A]

  /**
   * If `cond` is `false`, retries;
   * otherwise, succeeds with `()`.
   *
   * @see [[Txn.retry]]
   */
  final def check(cond: Boolean): Txn[Unit] =
    if (cond) unit else retry

  /**
   * Equivalent to the following implementation:
   *
   * {{{
   * def tailRecM[A, B](a: A)(f: A => Txn[Either[A, B]]): Txn[B] = {
   *   flatMap(f(a)) {
   *     case Left(a)  => tailRecM(a)(f)
   *     case Right(b) => pure(b)
   *   }
   * }
   * }}}
   *
   * @see [[cats.Monad#tailRecM]]
   */
  final def tailRecM[A, B](a: A)(f: A => Txn[Either[A, B]]): Txn[B] =
    Rxn.tailRecMImpl(a)(f.asInstanceOf[Function1[A, Rxn[Either[A, B]]]])

  /** @see [[cats.Defer#defer]] */
  final def defer[A](fa: => Txn[A]): Txn[A] =
    Rxn.unsafe.suspendImpl { fa.impl }

  private[choam] final def merge[A](txns: List[Txn[A]]): Txn[A] = // TODO: should this be public?
    txns.reduceLeftOption(_ orElse _).getOrElse(throw new IllegalArgumentException)

  /** Generates a unique token */
  final def unique: Txn[Unique.Token] =
    Rxn.uniqueImpl

  /** Generates a random [[java.util.UUID]] */
  final def newUuid: Txn[UUID] =
    Rxn.newUuidImpl

  private[choam] final object unsafe {

    trait WithLocal[A, R] {
      def apply[G[_]](local: TxnLocal[G, A], lift: Txn ~> G, inst: TxnLocal.Instances[G]): G[R]
    }

    final def withLocal[A, R](initial: A, body: WithLocal[A, R]): Txn[R] = {
      TxnLocal.withLocal(initial, body)
    }

    @inline
    private[choam] final def delay[A](uf: => A): Txn[A] =
      Rxn.unsafe.delayImpl[A](uf)

    private[choam] final def suspend[A](uf: => Txn[A]): Txn[A] =
      delay(uf).flatten

    @inline
    private[choam] final def delayContext[A](uf: Mcas.ThreadContext => A): Txn[A] =
      Rxn.unsafe.delayContextImpl(uf)

    private[choam] final def suspendContext[A](uf: Mcas.ThreadContext => Txn[A]): Txn[A] =
      delayContext(uf).flatten

    final def panic[A](ex: Throwable): Txn[A] =
      Rxn.unsafe.panicImpl(ex)

    /** Only for testing! */
    private[choam] final def retryUnconditionally[A]: Txn[A] =
      Rxn.unsafe.retryImpl[A]

    /** Only for testing! */
    private[choam] final def plus[A](t1: Txn[A], t2: Txn[A]): Txn[A] = {
      t1.asInstanceOf[RxnImpl[A]] + t2.asInstanceOf[RxnImpl[A]]
    }
  }

  final class InvariantSyntax[A](private val self: Txn[A]) extends AnyVal {
    final def commit[F[_]](implicit F: Transactive[F]): F[A] =
      F.commit(self)
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
      fa.product(fb)
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
      // Note: see the long comment in Rxn.deferInstance
      val ref = new scala.runtime.ObjectRef[Txn[A]](null)
      val res = fn(defer {
        self.acquireFence()
        ref.elem
      })
      ref.elem = res
      self.releaseFence()
      res
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

  implicit final def uuidGenInstance: UUIDGen[Txn] =
    _uuidGenInstance

  private[this] val _uuidGenInstance: UUIDGen[Txn] = new UUIDGen[Txn] {
    final override def randomUUID: Txn[UUID] =
      self.newUuid
  }
}

private[stm] sealed abstract class TxnInstances1 extends TxnSyntax0 { self: Txn.type =>

  implicit final def monoidInstance[B](implicit B: Monoid[B]): Monoid[Txn[B]] = new Monoid[Txn[B]] {
    final override def combine(x: Txn[B], y: Txn[B]): Txn[B] =
      x.map2(y) { (b1, b2) => B.combine(b1, b2) }
    final override def empty: Txn[B] =
      Txn.pure(B.empty)
  }
}

private[stm] sealed abstract class TxnSyntax0 extends TxnCompanionPlatform { self: Txn.type =>

  import scala.language.implicitConversions

  implicit final def invariantSyntax[A](self: Txn[A]): Txn.InvariantSyntax[A] =
    new Txn.InvariantSyntax(self)
}
