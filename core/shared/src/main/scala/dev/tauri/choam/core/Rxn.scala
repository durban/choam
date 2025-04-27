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

import java.util.{ UUID, IdentityHashMap }

import cats.{ ~>, Align, Applicative, Defer, Functor, StackSafeMonad, Monoid, MonoidK, Semigroup, Show }
import cats.arrow.ArrowChoice
import cats.data.{ Ior, State }
import cats.mtl.Local
import cats.effect.kernel.{ Async, Clock, Cont, Unique, MonadCancel, Ref => CatsRef }
import cats.effect.std.{ Random, SecureRandom, UUIDGen }

import internal.mcas.{ MemoryLocation, Mcas, LogEntry, McasStatus, Descriptor, AbstractDescriptor, Consts, Hamt, Version }

/**
 * An effectful function from `A` to `B`; when executed,
 * it may update any number of [[Ref]]s atomically. (It
 * may also create new [[Ref]]s.)
 *
 * These functions are composable (see below), and composition
 * preserves their atomicity. That is, all affected [[Ref]]s
 * will be updated atomically.
 *
 * A [[Rxn]] forms an [[cats.arrow.Arrow Arrow]] (more
 * specifically, an [[cats.arrow.ArrowChoice ArrowChoice]]).
 * It also forms a [[cats.Monad Monad]] in `B`; however, consider
 * using the arrow combinators (when possible) instead of `flatMap`
 * (since a static combination of `Rxn`s may be more performant).
 *
 * The relation between [[Rxn]] and [[Axn]] is approximately
 * `Rxn[A, B] ≡ (A => Axn[B])`; or, alternatively
 * `Axn[A] ≡ Rxn[Any, A]`.
 */
sealed abstract class Rxn[-A, +B] { // short for 'reaction'

  /*
   * An implementation similar to reagents, described in [Reagents: Expressing and
   * Composing Fine-grained Concurrency](https://web.archive.org/web/20220214132428/https://www.ccis.northeastern.edu/home/turon/reagents.pdf)
   * by Aaron Turon; originally implemented at [aturon/ChemistrySet](
   * https://github.com/aturon/ChemistrySet).
   *
   * This implementation is significantly simplified by the fact
   * that offers and permanent failure are not implemented. As a
   * consequence, these `Rxn`s are always lock-free (provided
   * that the underlying k-CAS implementation is lock-free, and
   * that `unsafe*` operations are not used, and there is no
   * infinite recursion).
   *
   * On the other hand, this implementation uses an optimized and
   * stack-safe interpreter (see `interpreter`). A limited version
   * of an `Exchanger` is also implemented, which can be used to
   * implement elimination arrays. (The `Exchanger` by itself could
   * cause indefinite retries, so it must always be combined with
   * a lock-free operation.)
   *
   * Another difference is the referentially transparent ("purely
   * functional") API. All side-effecting APIs are prefixed by
   * `unsafe`. (But not all `unsafe` APIs are side-effecting, some
   * of them are `unsafe` for another reason.)
   *
   * We also offer [*opacity*](https://nbronson.github.io/scala-stm/semantics.html#opacity),
   * a consistency guarantee of the read values visible inside a
   * running `Rxn`.
   *
   * Finally (unlike with reagents), two `Rxn`s which touch the same
   * `Ref`s are composable with each other. This allows multiple
   * reads and writes to the same `Ref` in one `Rxn`. (`Exchanger` is
   * an exception, this is part of the reason it is `unsafe`).
   *
   * Existing reagent implementations:
   * - https://github.com/aturon/Caper (Racket)
   * - https://github.com/ocamllabs/reagents (OCaml)
   */

  /*
   * Implementation note: in some cases, composing
   * `Rxn`s with `>>>` (or `*>`) will be faster
   * than using `flatMap`. An example (with measurements)
   * is in `ArrowBench`.
   *
   * TODO: More benchmarks needed to determine exactly
   * TODO: what it is that makes them faster. Also,
   * TODO: maybe we could optimize `flatMap`.
   */

  /**
   * Tag for the interpreter (see `interpreter`)
   *
   * This attempts to be an optimization, inspired by an old optimization in
   * the Scala compiler for matching on sealed subclasses
   * (see https://github.com/scala/scala/commit/b98eb1d74141a4159539d373e6216e799d6b6dcd).
   * Except we do it by hand, which is ugly, but might be worth it.
   *
   * In Cats Effect 3 the IO/SyncIO runloop also uses something like this
   * (see https://github.com/typelevel/cats-effect/blob/v3.0.2/core/shared/src/main/scala/cats/effect/SyncIO.scala#L195),
   *
   * The ZIO runloop seems to do something similar too
   * (see https://github.com/zio/zio/blob/v1.0.6/core/shared/src/main/scala/zio/internal/FiberContext.scala#L320).
   *
   * The idea is to `match` on `r.tag` instead of `r` itself. That match
   * should be compiled to a JVM tableswitch instruction. Which is supposed
   * to be very fast. The match arms require `.asInstanceOf`, which is unsafe
   * and makes maintenance harder. However, if there are a lot of cases,
   * a chain of instanceof/checkcast instructions could be slower.
   *
   * TODO: Check if it's indeed faster than a simple `match` (apparently "tag"
   * TODO: was removed from the Scala compiler because it was not worth it).
   */
  private[core] def tag: Byte

  def + [X <: A, Y >: B](that: Rxn[X, Y]): Rxn[X, Y]

  def >>> [C](that: Rxn[B, C]): Rxn[A, C]

  def × [C, D](that: Rxn[C, D]): Rxn[(A, C), (B, D)]

  def * [X <: A, C](that: Rxn[X, C]): Rxn[X, (B, C)]

  def product[X <: A, C](that: Rxn[X, C]): Rxn[X, (B, C)]

  def ? : Rxn[A, Option[B]]

  def attempt: Rxn[A, Option[B]]

  def maybe: Rxn[A, Boolean]

  def map[C](f: B => C): Rxn[A, C]

  def as[C](c: C): Rxn[A, C]

  def void: Rxn[A, Unit]

  // FIXME: do we need this?
  def dup: Rxn[A, (B, B)]

  def contramap[C](f: C => A): Rxn[C, B]

  def provide(a: A): Axn[B]

  def dimap[C, D](f: C => A)(g: B => D): Rxn[C, D]

  def toFunction: A => Axn[B]

  def map2[X <: A, C, D](that: Rxn[X, C])(f: (B, C) => D): Rxn[X, D]

  def <* [X <: A, C](that: Rxn[X, C]): Rxn[X, B]

  def productL [X <: A, C](that: Rxn[X, C]): Rxn[X, B]

  def *> [X <: A, C](that: Rxn[X, C]): Rxn[X, C]

  def productR[X <: A, C](that: Rxn[X, C]): Rxn[X, C]

  def first[C]: Rxn[(A, C), (B, C)]

  def second[C]: Rxn[(C, A), (C, B)]

  def flatMap[X <: A, C](f: B => Rxn[X, C]): Rxn[X, C]

  def flatMapF[C](f: B => Axn[C]): Rxn[A, C]

  def >> [X <: A, C](that: => Rxn[X, C]): Rxn[X, C]

  def flatTap(rxn: Rxn[B, Unit]): Rxn[A, B]

  def flatten[C](implicit ev: B <:< Axn[C]): Rxn[A, C]

  def postCommit(pc: Rxn[B, Unit]): Rxn[A, B]

  /**
   * Execute the [[Rxn]] with the specified input `a`.
   *
   * This method is `unsafe` because it performs side-effects.
   *
   * @param a the input to the [[Rxn]].
   * @param rt the [[ChoamRuntime]] which will run the [[Rxn]].
   * @return the result of the executed [[Rxn]].
   */
  final def unsafePerform(
    a: A,
    rt: ChoamRuntime,
  ): B = this.unsafePerform(a, rt.mcasImpl, RetryStrategy.Default)

  /**
   * Execute the [[Rxn]] with the specified input `a`.
   *
   * This method is `unsafe` because it performs side-effects.
   *
   * @param a the input to the [[Rxn]].
   * @param rt the [[ChoamRuntime]] which will run the [[Rxn]].
   * @param strategy the retry strategy to use.
   * @return the result of the executed [[Rxn]].
   */
  final def unsafePerform(
    a: A,
    rt: ChoamRuntime,
    strategy: RetryStrategy.Spin,
  ): B = this.unsafePerform(a, rt.mcasImpl, strategy)

  private[choam] final def unsafePerform(
    a: A,
    mcas: Mcas,
    strategy: RetryStrategy.Spin = RetryStrategy.Default,
  ): B = {
    new Rxn.InterpreterState[A, B](
      rxn = this,
      x = a,
      mcas = mcas,
      strategy = strategy,
      isStm = false,
    ).interpretSync()
  }

  final def perform[F[_], X >: B](
    a: A,
    rt: ChoamRuntime,
  )(implicit F: Async[F]): F[X] = this.perform(a, rt, RetryStrategy.Default)

  final def perform[F[_], X >: B](
    a: A,
    rt: ChoamRuntime,
    strategy: RetryStrategy,
  )(implicit F: Async[F]): F[X] = this.perform(a, rt.mcasImpl, strategy)

  private[choam] final def perform[F[_], X >: B](
    a: A,
    mcas: Mcas,
    strategy: RetryStrategy = RetryStrategy.Default,
  )(implicit F: Async[F]): F[X] = {
    F.uncancelable { poll =>
      F.defer {
        new Rxn.InterpreterState[A, X](
          this,
          a,
          mcas = mcas,
          strategy = strategy,
          isStm = false,
        ).interpretAsync(poll)(F)
      }
    }
  }

  /** Only for tests/benchmarks */
  private[choam] final def unsafePerformInternal0(
    a: A,
    ctx: Mcas.ThreadContext,
  ): B = {
    new Rxn.InterpreterState[A, B](
      this,
      a,
      ctx.impl,
      strategy = RetryStrategy.Default,
      isStm = false,
    ).interpretSyncWithContext(ctx)
  }

  /** Only for tests/benchmarks */
  private[choam] final def unsafePerformInternal(
    a: A,
    ctx: Mcas.ThreadContext,
    maxBackoff: Int = BackoffPlatform.maxPauseDefault,
    randomizeBackoff: Boolean = BackoffPlatform.randomizePauseDefault,
  ): B = {
    // TODO: this allocation can hurt us in benchmarks!
    val str = RetryStrategy
      .Default
      .withMaxSpin(maxBackoff)
      .withRandomizeSpin(randomizeBackoff)
    new Rxn.InterpreterState[A, B](
      this,
      a,
      ctx.impl,
      strategy = str,
      isStm = false,
    ).interpretSyncWithContext(ctx)
  }

  private[choam] final def performStm[F[_], X >: B](
    a: A,
    mcas: Mcas,
    strategy: RetryStrategy,
  )(implicit F: Async[F]): F[X] = {
    // It is unsafe to accept a `Stepper` through
    // this method, since it could be a `Stepper[G]`,
    // where `G` is different form `F`:
    require(!strategy.isDebug)
    this.performStmInternal[F, X](a, mcas, strategy)
  }

  private[choam] final def performStmWithStepper[F[_], X >: B](
    a: A,
    mcas: Mcas,
    stepper: RetryStrategy.Internal.Stepper[F],
  )(implicit F: Async[F]): F[X] = {
    this.performStmInternal[F, X](a, mcas, stepper)
  }

  private[this] final def performStmInternal[F[_], X >: B](
    a: A,
    mcas: Mcas,
    strategy: RetryStrategy,
  )(implicit F: Async[F]): F[X] = {
    require(strategy.canSuspend)
    F.uncancelable { poll =>
      F.defer {
        new Rxn.InterpreterState[A, X](
          this,
          a,
          mcas = mcas,
          strategy = strategy,
          isStm = true,
        ).interpretAsync(poll)(F)
      }
    }
  }

  override def toString: String
}

private[choam] sealed abstract class RxnImpl[-A, +B]
  extends Rxn[A, B] with Txn.UnsealedTxn[B] {

  final override def + [X <: A, Y >: B](that: Rxn[X, Y]): RxnImpl[X, Y] =
    new Rxn.Choice[X, Y](this, that)

  final override def >>> [C](that: Rxn[B, C]): RxnImpl[A, C] =
    new Rxn.AndThen[A, B, C](this, that)

  final override def × [C, D](that: Rxn[C, D]): RxnImpl[(A, C), (B, D)] =
    new Rxn.AndAlso[A, B, C, D](this, that)

  final override def * [X <: A, C](that: Rxn[X, C]): RxnImpl[X, (B, C)] =
    (this × that).contramap[X](x => (x, x))

  final override def product[X <: A, C](that: Rxn[X, C]): Rxn[X, (B, C)] =
    this * that

  final override def ? : Rxn[A, Option[B]] =
    this.attempt

  final override def attempt: Rxn[A, Option[B]] =
    this.map(Some(_)) + Rxn.pure[Option[B]](None)

  final override def maybe: Rxn[A, Boolean] =
    this.as(true) + Rxn.pure(false)

  final override def map[C](f: B => C): RxnImpl[A, C] =
    new Rxn.Map_(this, f)

  final override def as[C](c: C): RxnImpl[A, C] =
    new Rxn.As(this, c)

  final override def void: RxnImpl[A, Unit] =
    this.as(())

  // FIXME: do we need this?
  final override def dup: Rxn[A, (B, B)] =
    this.map { b => (b, b) }

  final override def contramap[C](f: C => A): RxnImpl[C, B] =
    Rxn.liftImpl(f) >>> this

  final override def provide(a: A): Axn[B] =
    new Rxn.Provide[A, B](this, a)

  final override def dimap[C, D](f: C => A)(g: B => D): Rxn[C, D] =
    this.contramap(f).map(g)

  final override def toFunction: A => Axn[B] = { (a: A) =>
    this.provide(a)
  }

  final override def map2[X <: A, C, D](that: Rxn[X, C])(f: (B, C) => D): RxnImpl[X, D] =
    new Rxn.Map2(this, that, f)

  final override def <* [X <: A, C](that: Rxn[X, C]): Rxn[X, B] =
    this.productL(that)

  final override def productL [X <: A, C](that: Rxn[X, C]): RxnImpl[X, B] =
    (this * that).map(_._1)

  final override def *> [X <: A, C](that: Rxn[X, C]): Rxn[X, C] =
    this.productR(that)

  final override def productR[X <: A, C](that: Rxn[X, C]): RxnImpl[X, C] =
    new Rxn.ProductR[X, B, C](this, that)

  final override def first[C]: Rxn[(A, C), (B, C)] =
    this × Rxn.identity[C]

  final override def second[C]: Rxn[(C, A), (C, B)] =
    Rxn.identity[C] × this

  final override def flatMap[X <: A, C](f: B => Rxn[X, C]): Rxn[X, C] =
    new Rxn.FlatMap(this, f)

  final override def flatMapF[C](f: B => Axn[C]): RxnImpl[A, C] =
    new Rxn.FlatMapF(this, f)

  // TODO: optimize
  final override def >> [X <: A, C](that: => Rxn[X, C]): Rxn[X, C] =
    this.flatMap { _ => that }

  final override def flatTap(rxn: Rxn[B, Unit]): Rxn[A, B] =
    this.flatMapF { b => rxn.provide(b).as(b) } // TODO: is this really better than the one with flatMap?

  final override def flatten[C](implicit ev: B <:< Axn[C]): RxnImpl[A, C] =
    this.flatMapF(ev)

  final override def postCommit(pc: Rxn[B, Unit]): Rxn[A, B] =
    this >>> Rxn.postCommit[B](pc)

  // STM:

  final override def flatMap[C](f: B => Txn[C]): Txn[C] = {
    this.flatMapF(f.asInstanceOf[Function1[B, Axn[C]]])
  }

  final override def map2[C, D](that: Txn[C])(f: (B, C) => D): Txn[D] = {
    this.map2[A, C, D](that.impl)(f)
  }

  final override def productR[C](that: Txn[C]): Txn[C] = {
    this.productR[A, C](that.impl)
  }

  final override def *> [C](that: Txn[C]): Txn[C] = {
    this.productR[A, C](that.impl)
  }

  final override def productL[C](that: Txn[C]): Txn[B] = {
    this.productL[A, C](that.impl)
  }

  final override def <* [C](that: Txn[C]): Txn[B] = {
    this.productL[A, C](that.impl)
  }

  final override def product[C](that: Txn[C]): Txn[(B, C)] = {
    this * that.impl
  }

  final override def orElse[Y >: B](that: Txn[Y]): Txn[Y] = {
    new Rxn.OrElse(this, that.impl)
  }

  final override def commit[F[_], X >: B](implicit F: Transactive[F]): F[X] = {
    F.commit(this)
  }

  private[core] final override def impl: RxnImpl[Any, B] =
    this.asInstanceOf[RxnImpl[Any, B]] // Note: this is unsafe in general, we must take care to only use it on Txns

  // /STM
}

/** This is specifically only for `Ref` to use! */
private[choam] abstract class RefGetAxn[B] extends RxnImpl[Any, B] {
  private[core] final override def tag = 8
}

object Rxn extends RxnInstances0 {

  private[this] final val interruptCheckPeriod =
    16384

  // API:

  @inline
  final def pure[A](a: A): Axn[A] =
    pureImpl(a)

  private[core] final def pureImpl[A](a: A): RxnImpl[Any, A] =
    new Rxn.Pure[A](a)

  /** Old name of `pure` */
  private[choam] final def ret[A](a: A): Axn[A] =
    pure(a)

  final def identity[A]: Rxn[A, A] =
    lift(a => a)

  @inline
  final def lift[A, B](f: A => B): Rxn[A, B] =
    liftImpl(f)

  private[core] final def liftImpl[A, B](f: A => B): RxnImpl[A, B] =
    new Rxn.Lift(f)

  private[this] val _unit: RxnImpl[Any, Unit] =
    pureImpl(())

  @inline
  final def unit[A]: Rxn[A, Unit] =
    unitImpl[A]

  private[core] final def unitImpl[A]: RxnImpl[A, Unit] =
    _unit

  @inline
  final def panic[A](ex: Throwable): Axn[A] = // TODO:0.5: should this be in `unsafe`?
    panicImpl(ex)

  private[core] final def panicImpl[A](ex: Throwable): RxnImpl[Any, A] =
    unsafe.delayImpl[Any, A] { _ => throw ex }

  private[choam] final def assert(cond: Boolean): Axn[Unit] =
    if (cond) unit[Any] else panic[Unit](new AssertionError)

  final def computed[A, B](f: A => Axn[B]): Rxn[A, B] =
    new Rxn.Computed(f)

  final def postCommit[A](pc: Rxn[A, Unit]): Rxn[A, A] =
    new Rxn.PostCommit[A](pc)

  @inline
  final def tailRecM[X, A, B](a: A)(f: A => Rxn[X, Either[A, B]]): Rxn[X, B] =
    tailRecMImpl(a)(f)

  private[core] final def tailRecMImpl[X, A, B](a: A)(f: A => Rxn[X, Either[A, B]]): RxnImpl[X, B] =
    new Rxn.TailRecM[X, A, B](a, f)

  // Utilities:

  private[this] val _fastRandom: Random[Axn] =
    random.newFastRandom

  private[this] val _secureRandom: SecureRandom[Axn] =
    random.newSecureRandom

  private[this] val _unique: RxnImpl[Any, Unique.Token] =
    Axn.unsafe.delayImpl { new Unique.Token() }

  @inline
  final def unique: Axn[Unique.Token] =
    uniqueImpl

  private[core] final def uniqueImpl: RxnImpl[Any, Unique.Token] =
    _unique

  @inline
  final def newUuid: Axn[UUID] =
    newUuidImpl

  private[core] final val newUuidImpl: RxnImpl[Any, UUID] =
    random.newUuidImpl

  final def fastRandom: Random[Axn] =
    _fastRandom

  final def secureRandom: SecureRandom[Axn] =
    _secureRandom

  final def deterministicRandom(initialSeed: Long): Axn[random.SplittableRandom[Axn]] =
    deterministicRandom(initialSeed, Ref.AllocationStrategy.Default)

  final def deterministicRandom(initialSeed: Long, str: Ref.AllocationStrategy): Axn[random.SplittableRandom[Axn]] =
    random.deterministicRandom(initialSeed, str)

  final def memoize[A](axn: Axn[A], str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default): Axn[Memo[A]] =
    Memo(axn, str)

  private[choam] final object ref {

    private[choam] final def upd[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): Rxn[B, C] =
      new Rxn.Upd(r.loc, f)

    private[choam] final def updWith[A, B, C](r: Ref[A])(f: (A, B) => Axn[(A, C)]): Rxn[B, C] =
      new Rxn.UpdWith[A, B, C](r.loc, f)
  }

  private[choam] final object loc {

    private[choam] final def upd[A, B, C](r: MemoryLocation[A])(f: (A, B) => (A, C)): RxnImpl[B, C] =
      new Rxn.Upd(r, f)
  }

  final object unsafe {

    trait WithLocal[A, I, R] {
      def apply[G[_, _]](
        local: RxnLocal[G, A],
        lift: RxnLocal.Lift[Rxn, G],
        instances: RxnLocal.Instances[G],
      ): G[I, R]
    }

    trait WithLocalArray[A, I, R] {
      def apply[G[_, _]](
        arr: RxnLocal.Array[G, A],
        lift: RxnLocal.Lift[Rxn, G],
        instances: RxnLocal.Instances[G],
      ): G[I, R]
    }

    @inline
    final def withLocal[A, I, R](initial: A, body: WithLocal[A, I, R]): Rxn[I, R] =
      RxnLocal.withLocal(initial, body)

    @inline
    final def withLocalArray[A, I, R](size: Int, initial: A, body: WithLocalArray[A, I, R]): Rxn[I, R] =
      RxnLocal.withLocalArray(size, initial, body)

    sealed abstract class Ticket[A] {
      def unsafePeek: A
      def unsafeSet(nv: A): Axn[Unit]
      def unsafeIsReadOnly: Boolean
      final def unsafeValidate: Axn[Unit] =
        this.unsafeSet(this.unsafePeek)
    }

    private[Rxn] final class TicketImpl[A](hwd: LogEntry[A])
      extends Ticket[A] {

      final def unsafePeek: A =
        hwd.nv

      final def unsafeSet(nv: A): Axn[Unit] =
        new Rxn.TicketWrite(hwd, nv)

      final def unsafeIsReadOnly: Boolean =
        hwd.readOnly
    }

    private[choam] final def directRead[A](r: Ref[A]): Axn[A] =
      new Rxn.DirectRead[A](r.loc)

    final def ticketRead[A](r: Ref[A]): Axn[unsafe.Ticket[A]] =
      new Rxn.TicketRead[A](r.loc)

    final def unread[A](r: Ref[A]): Axn[Unit] =
      new Rxn.Unread(r)

    private[choam] final def cas[A](r: Ref[A], ov: A, nv: A): Axn[Unit] =
      new Rxn.Cas[A](r.loc, ov, nv)

    @inline
    private[choam] final def retry[A]: Axn[A] =
      retryImpl[A]

    private[core] final def retryImpl[A]: RxnImpl[Any, A] =
      Rxn._AlwaysRetry.asInstanceOf[RxnImpl[Any, A]]

    @inline
    private[choam] final def delay[A, B](uf: A => B): Rxn[A, B] =
      delayImpl(uf)

    @inline
    private[choam] final def delayImpl[A, B](uf: A => B): RxnImpl[A, B] =
      liftImpl(uf)

    private[choam] final def suspend[A, B](uf: A => Axn[B]): Rxn[A, B] =
      delay(uf).flatten // TODO: optimize

    private[choam] final def suspend[A, B](uf: => Rxn[A, B]): Rxn[A, B] =
      Axn.unsafe.delay(uf).flatMap { x => x } // TODO: optimize

    // TODO: Calling `unsafePerform` (or similar) inside
    // TODO: `uf` is dangerous; currently it only messes
    // TODO: up exchanger statistics; in the future, who knows...
    @inline
    private[choam] final def delayContext[A](uf: Mcas.ThreadContext => A): Axn[A] =
      delayContextImpl(uf)

    private[choam] final def delayContextImpl[A](uf: Mcas.ThreadContext => A): RxnImpl[Any, A] =
      new Rxn.Ctx[A](uf)

    // TODO:0.5: suspendContext[A, B](uf: (A, ThreadContext) => Axn[B]): Rxn[A, B]
    // TODO:0.5: and use it in MsQueue
    private[choam] final def suspendContext[A](uf: Mcas.ThreadContext => Axn[A]): Axn[A] =
      this.delayContext(uf).flatten // TODO: optimize

    private[choam] final def exchanger[A, B]: Axn[Exchanger[A, B]] =
      Exchanger.apply[A, B]

    /**
     * This is not unsafe by itself, but it is only useful
     * if there are other unsafe things going on (validation
     * is handled automatically otherwise). This is why it
     * is part of the `unsafe` API.
     */
    final def forceValidate: Axn[Unit] =
      new Rxn.ForceValidate
  }

  private[core] final object internal {

    final def exchange[A, B](ex: ExchangerImpl[A, B]): Rxn[A, B] =
      new Rxn.Exchange[A, B](ex)

    final def finishExchange[D](
      hole: Ref[Exchanger.NodeResult[D]],
      restOtherContK: ListObjStack.Lst[Any],
      lenSelfContT: Int,
    ): Rxn[D, Unit] = new Rxn.FinishExchange(hole, restOtherContK, lenSelfContT)

    final def newLocal(local: InternalLocal): RxnImpl[Any, Unit] =
      new Rxn.LocalNewEnd(local, isEnd = false)

    final def endLocal(local: InternalLocal): RxnImpl[Any, Unit] =
      new Rxn.LocalNewEnd(local, isEnd = true)
  }

  private[choam] final object StmImpl {

    private[choam] final def retryWhenChanged[A]: RxnImpl[Any, A] =
      _RetryWhenChanged.asInstanceOf[RxnImpl[Any, A]]
  }

  // Representation:

  /** Only the interpreter can use this! */
  private final class Commit[A]() extends RxnImpl[A, A] {
    private[core] final override def tag = 0
    final override def toString: String = "Commit()"
  }

  private final class AlwaysRetry[A, B]() extends RxnImpl[A, B] {
    private[core] final override def tag = 1
    final override def toString: String = "AlwaysRetry()"
  }

  private[core] val _AlwaysRetry: RxnImpl[Any, Any] =
    new AlwaysRetry

  private final class PostCommit[A](val pc: Rxn[A, Unit]) extends RxnImpl[A, A] {
    private[core] final override def tag = 2
    final override def toString: String = s"PostCommit(${pc})"
  }

  private final class Lift[A, B](val func: A => B) extends RxnImpl[A, B] {
    private[core] final override def tag = 3
    final override def toString: String = "Lift(<function>)"
  }

  private final class Computed[A, B](val f: A => Axn[B]) extends RxnImpl[A, B] {
    private[core] final def tag = 4
    final override def toString: String = "Computed(<function>)"
  }

  private[this] final class RetryWhenChanged[A]() extends RxnImpl[Any, A] { // STM
    private[core] final override def tag = 5
    final override def toString: String = "RetryWhenChanged()"
  }

  private[this] val _RetryWhenChanged: RxnImpl[Any, Any] =
    new RetryWhenChanged[Any]

  private[core] final class Choice[A, B](val left: Rxn[A, B], val right: Rxn[A, B]) extends RxnImpl[A, B] {
    private[core] final override def tag = 6
    final override def toString: String = s"Choice(${left}, ${right})"
  }

  private final class Cas[A](val ref: MemoryLocation[A], val ov: A, val nv: A) extends RxnImpl[Any, Unit] {
    private[core] final override def tag = 7
    final override def toString: String = s"Cas(${ref}, ${ov}, ${nv})"
  }

  // Note: tag = 8 is RefGetAxn (above)

  private[core] final class Map2[A, B, C, D](val left: Rxn[A, B], val right: Rxn[A, C], val f: (B, C) => D) extends RxnImpl[A, D] {
    private[core] final override def tag = 9
    final override def toString: String = s"Map2(${left}, ${right}, <function>)"
  }

  private final class Upd[A, B, X](val ref: MemoryLocation[X], val f: (X, A) => (X, B)) extends RxnImpl[A, B] {
    private[core] final override def tag = 10
    final override def toString: String = s"Upd(${ref}, <function>)"
  }

  private final class TicketWrite[A](val hwd: LogEntry[A], val newest: A) extends RxnImpl[Any, Unit] {
    private[core] final override def tag = 11
    final override def toString: String = s"TicketWrite(${hwd}, ${newest})"
  }

  private final class DirectRead[A](val ref: MemoryLocation[A]) extends RxnImpl[Any, A] {
    private[core] final override def tag = 12
    final override def toString: String = s"DirectRead(${ref})"
  }

  private final class Exchange[A, B](val exchanger: ExchangerImpl[A, B]) extends RxnImpl[A, B] {
    private[core] final override def tag = 13
    final override def toString: String = s"Exchange(${exchanger})"
  }

  private[core] final class AndThen[A, B, C](val left: Rxn[A, B], val right: Rxn[B, C]) extends RxnImpl[A, C] {
    private[core] final override def tag = 14
    final override def toString: String = s"AndThen(${left}, ${right})"
  }

  private[core] final class AndAlso[A, B, C, D](val left: Rxn[A, B], val right: Rxn[C, D]) extends RxnImpl[(A, C), (B, D)] {
    private[core] final override def tag = 15
    final override def toString: String = s"AndAlso(${left}, ${right})"
  }

  /** Only the interpreter can use this! */
  private final class Done[A](val result: A) extends RxnImpl[Any, A] {
    private[core] final override def tag = 16
    final override def toString: String = s"Done(${result})"
  }

  private final class Ctx[A](val uf: Mcas.ThreadContext => A) extends RxnImpl[Any, A] {
    private[core] final override def tag = 17
    final override def toString: String = s"Ctx(<block>)"
  }

  private[core] final class Provide[A, B](val rxn: Rxn[A, B], val a: A) extends RxnImpl[Any, B] {
    private[core] final override def tag = 18
    final override def toString: String = s"Provide(${rxn}, ${a})"
  }

  private final class UpdWith[A, B, C](val ref: MemoryLocation[A], val f: (A, B) => Axn[(A, C)]) extends RxnImpl[B, C] {
    private[core] final override def tag = 19
    final override def toString: String = s"UpdWith(${ref}, <function>)"
  }

  private[core] final class As[A, B, C](val rxn: Rxn[A, B], val c: C) extends RxnImpl[A, C] {
    private[core] final override def tag = 20
    final override def toString: String = s"As(${rxn}, ${c})"
  }

  /** Only the interpreter/exchanger can use this! */
  private final class FinishExchange[D](
    val hole: Ref[Exchanger.NodeResult[D]],
    val restOtherContK: ListObjStack.Lst[Any],
    val lenSelfContT: Int,
  ) extends RxnImpl[D, Unit] {
    private[core] final override def tag = 21
    final override def toString: String = {
      val rockLen = ListObjStack.Lst.length(this.restOtherContK)
      s"FinishExchange(${hole}, <ListObjStack.Lst of length ${rockLen}>, ${lenSelfContT})"
    }
  }

  private final class TicketRead[A](val ref: MemoryLocation[A]) extends RxnImpl[Any, Rxn.unsafe.Ticket[A]] {
    private[core] final override def tag = 22
    final override def toString: String = s"TicketRead(${ref})"
  }

  private final class ForceValidate() extends RxnImpl[Any, Unit] {
    private[core] final override def tag = 23
    final override def toString: String = s"ForceValidate()"
  }

  private final class Pure[A](val a: A) extends RxnImpl[Any, A] {
    private[core] final override def tag = 24
    final override def toString: String = s"Pure(${a})"
  }

  private[core] final class ProductR[A, B, C](val left: Rxn[A, B], val right: Rxn[A, C]) extends RxnImpl[A, C] {
    private[core] final override def tag = 25
    final override def toString: String = s"ProductR(${left}, ${right})"
  }

  private[core] final class FlatMapF[A, B, C](val rxn: Rxn[A, B], val f: B => Axn[C]) extends RxnImpl[A, C] {
    private[core] final override def tag = 26
    final override def toString: String = s"FlatMapF(${rxn}, <function>)"
  }

  private[core] final class FlatMap[A, B, C](val rxn: Rxn[A, B], val f: B => Rxn[A, C]) extends RxnImpl[A, C] {
    private[core] final override def tag = 27
    final override def toString: String = s"FlatMap(${rxn}, <function>)"
  }

  /** Only the interpreter can use this! */
  private sealed abstract class SuspendUntil extends RxnImpl[Any, Nothing] {

    private[core] final override def tag = 28

    def toF[F[_]](
      mcasImpl: Mcas,
      mcasCtx: Mcas.ThreadContext,
    )(implicit F: Async[F]): F[Rxn[Any, Any]]
  }

  private final class SuspendUntilBackoff(val token: Long) extends SuspendUntil {

    _assert(!Backoff2.isPauseToken(token))

    final override def toString: String =
      s"SuspendUntilBackoff(${token.toHexString})"

    final override def toF[F[_]](
      mcasImpl: Mcas,
      mcasCtx: Mcas.ThreadContext,
    )(implicit F: Async[F]): F[Rxn[Any, Any]] =
      F.as(Backoff2.tokenToF[F](token), null)
  }

  private final class SuspendWithStepper[F[_]](
    stepper: RetryStrategy.Internal.Stepper[F],
    nextRxn: F[Rxn[Any, Any]],
  ) extends SuspendUntil {

    final override def toString: String =
      s"SuspendWithStepper(...)"

    final override def toF[G[_]](
      mcasImpl: Mcas,
      mcasCtx: Mcas.ThreadContext,
    )(implicit G: Async[G]): G[Rxn[Any, Any]] = {
      // Note: these casts are "safe", since `performStmWithStepper`
      // sets things up so that `F` and `G` are the same.
      G.productR(G.flatten(stepper.newSuspension.asInstanceOf[G[G[Unit]]]))(nextRxn.asInstanceOf[G[Rxn[Any, Any]]])
    }
  }

  private final class SuspendUntilChanged(
    desc: AbstractDescriptor,
  ) extends SuspendUntil {

    final override def toString: String =
      s"SuspendUntilChanged($desc)"

    final override def toF[F[_]](
      mcasImpl: Mcas,
      mcasCtx: Mcas.ThreadContext,
    )(implicit F: Async[F]): F[Rxn[Any, Any]] = {
      if ((desc ne null) && (desc.size > 0)) {
        F.cont(new Cont[F, Rxn[Any, Any], Rxn[Any, Any]] {
          final override def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
            G.uncancelable[Rxn[Any, Any]] { poll =>
              G.flatten {
                lift(F.delay[G[Rxn[Any, Any]]] {
                  val rightNull: Either[Throwable, Rxn[Any, Any]] = Right(null)
                  val cb2 = { (_: Null) =>
                    resume(rightNull)
                  }
                  val refsAndCancelIds = subscribe(mcasImpl, mcasCtx, cb2)
                  if (refsAndCancelIds eq null) {
                    // some ref already changed, don't suspend:
                    G.pure(null)
                  } else {
                    val unsubscribe: F[Unit] = F.delay {
                      val (refs, cancelIds) = refsAndCancelIds
                      val len = refs.length
                      var idx = 0
                      while (idx < len) {
                        refs(idx).unsafeCancelListener(cancelIds(idx))
                        idx += 1
                      }
                    }
                    G.guarantee(poll(get), lift(unsubscribe))
                  }
                })
              }
            }
          }
        })
      } else {
        F.never // TODO: should we just throw an error?
      }
    }

    private[this] final def subscribe(
      mcasImpl: Mcas,
      mcasCtx: Mcas.ThreadContext,
      cb: Null => Unit,
    ): (Array[MemoryLocation.WithListeners], Array[Long]) = {
      val ctx = if (mcasImpl.isCurrentContext(mcasCtx)) {
        mcasCtx
      } else {
        mcasImpl.currentContext()
      }
      val size = this.desc.size
      val refs = new Array[MemoryLocation.WithListeners](size)
      val cancelIds = new Array[Long](size)
      var idx = 0
      idx = subscribeToDesc(ctx, cb, this.desc, refs, cancelIds, idx)
      if (idx == -1) {
        return null // scalafix:ok
      }
      _assert(idx == size)
      (refs, cancelIds)
    }

    private[this] final def subscribeToDesc(
      ctx: Mcas.ThreadContext,
      cb: Null => Unit,
      desc: AbstractDescriptor,
      refs: Array[MemoryLocation.WithListeners],
      cancelIds: Array[Long],
      startIdx: Int,
    ): Int = {
      val itr = desc.hwdIterator
      var idx = startIdx
      while (itr.hasNext) {
        val hwd = itr.next()
        val loc = hwd.address.withListeners
        val cancelId = loc.unsafeRegisterListener(ctx, cb, hwd.oldVersion)
        if (cancelId == Consts.InvalidListenerId) {
          // changed since we've seen it, we won't suspend:
          this.undoSubscribe(idx, refs, cancelIds)
          return -1 // scalafix:ok
        }
        refs(idx) = loc
        cancelIds(idx) = cancelId
        idx += 1
      }

      idx
    }

    private[this] final def undoSubscribe(
      count: Int,
      refs: Array[MemoryLocation.WithListeners],
      cancelIds: Array[Long],
    ): Unit = {
      var idx = 0
      while (idx < count) {
        refs(idx).unsafeCancelListener(cancelIds(idx))
        idx += 1
      }
      _assert({
        val len = refs.length
        var ok = true
        while (idx < len) {
          ok &= ((refs(idx) eq null) && (cancelIds(idx) == 0L))
          idx += 1
        }
        ok
      })
    }
  }

  private final class TailRecM[X, A, B](val a: A, val f: A => Rxn[X, Either[A, B]]) extends RxnImpl[X, B] {
    private[core] final override def tag = 29
    final override def toString: String = s"TailRecM(${a}, <function>)"
  }

  private[core] final class Map_[A, B, C](val rxn: Rxn[A, B], val f: B => C) extends RxnImpl[A, C] {
    private[core] final override def tag = 30
    final override def toString: String = s"Map_(${rxn}, <function>)"
  }

  private[core] final class OrElse[A, B](val left: Rxn[A, B], val right: Rxn[A, B]) extends RxnImpl[A, B] { // STM
    private[core] final override def tag = 31
    final override def toString: String = s"OrElse(${left}, ${right})"
  }

  private final class Unread[A](val ref: Ref[A]) extends RxnImpl[Any, Unit] {
    private[core] final override def tag = 32
    final override def toString: String = s"Unread(${ref})"
  }

  private final class LocalNewEnd(val local: InternalLocal, val isEnd: Boolean) extends RxnImpl[Any, Unit] {
    private[core] final override def tag = 33
    final override def toString: String = s"LocalNewEnd(${local}, ${isEnd})"
  }

  // Syntax helpers:

  final class InvariantSyntax[A, B](private val self: Rxn[A, B]) extends AnyVal {
    final def apply[F[_]](a: A)(implicit F: Reactive[F]): F[B] =
      F.apply(self, a)
  }

  final class AxnSyntax[A](private val self: Axn[A]) extends AnyVal {
    final def run[F[_]](implicit F: Reactive[F]): F[A] =
      F.run(self)
  }

  final class Tuple2RxnSyntax[A, B, C](private val self: Rxn[A, (B, C)]) extends AnyVal {
    final def left: Rxn[A, B] =
      self.map(_._1)
    final def right: Rxn[A, C] =
      self.map(_._2)
    final def split[X, Y](left: Rxn[B, X], right: Rxn[C, Y]): Rxn[A, (X, Y)] =
      self >>> (left × right)
  }

  // Interpreter:

  private[this] final class PostCommitResultMarker // TODO: make this a java enum?
  private[this] final val postCommitResultMarker =
    new PostCommitResultMarker

  private[core] final val commitSingleton: Rxn[Any, Any] = // TODO: make this a java enum?
    new Commit[Any]

  private[this] final val objStackWithOneCommit: ListObjStack.Lst[Any] = {
    val stack = new ListObjStack[Any]
    stack.push(commitSingleton)
    stack.takeSnapshot()
  }

  private[this] final def mkInitialContK(): ObjStack[Any] = {
    val ck = new ArrayObjStack[Any](initSize = 16)
    ck.push(commitSingleton)
    ck
  }

  final class MaxRetriesReached(val maxRetries: Int)
    extends Exception(s"reached maxRetries of ${maxRetries}")

  private final class InterpreterState[X, R](
    rxn: Rxn[X, R],
    x: X,
    mcas: Mcas,
    strategy: RetryStrategy,
    isStm: Boolean,
  ) extends Hamt.EntryVisitor[MemoryLocation[Any], LogEntry[Any], Rxn[Any, Any]] {

    private[this] val maxRetries: Int =
      strategy.maxRetriesInt

    private[this] val canSuspend: Boolean = {
      val cs = strategy.canSuspend
      _assert( // just to be sure:
        ((!cs) == strategy.isInstanceOf[RetryStrategy.Spin]) &&
        (cs || (!isStm))
      )
      cs
    }

    private[this] var ctx: Mcas.ThreadContext =
      null

    private[this] final def invalidateCtx(): Unit = {
      this.ctx = null
      this._stats = null
      this._exParams = null
    }

    private[this] var startRxn: Rxn[Any, Any] = rxn.asInstanceOf[Rxn[Any, Any]]
    private[this] var startA: Any = x

    private[this] var _desc: AbstractDescriptor =
      null

    private[this] final def desc: AbstractDescriptor = {
      if (_desc ne null) {
        _desc
      } else {
        if (this.mutable) {
          _desc = ctx.start()
        } else {
          _desc = ctx.startSnap()
        }
        _desc
      }
    }

    @tailrec
    private[this] final def descImm: Descriptor = {
      if (this.mutable) {
        this.convertToImmutable()
        this.descImm
      } else {
        this.desc.asInstanceOf[Descriptor]
      }
    }

    private[this] final def convertToImmutable(): Unit = {
      _assert(this.mutable)
      this.mutable = false
      if (this._desc ne null) {
        this.desc = ctx.snapshot(this.desc)
      }
      this.contK = this.contK.asInstanceOf[ArrayObjStack[Any]].toListObjStack()
    }

    @inline
    private[this] final def desc_=(d: AbstractDescriptor): Unit = {
      _assert(d ne null) // we want to be explicit, see `clearDesc`
      _desc = d
    }

    @inline
    private[this] final def clearDesc(): Unit = {
      _desc = null
    }

    private[this] val alts: ArrayObjStack[Any] = new ArrayObjStack[Any](initSize = 8)
    private[this] val stmAlts: ArrayObjStack[Any] = if (isStm) {
      new ArrayObjStack[Any](initSize = 8)
    } else {
      null
    }

    private[this] var locals: IdentityHashMap[InternalLocal, AnyRef] =
      null

    private[this] val contT: ByteStack = new ByteStack(initSize = 8)
    private[this] var contK: ObjStack[Any] = mkInitialContK()
    private[this] val pc: ListObjStack[Rxn[Any, Unit]] = new ListObjStack[Rxn[Any, Unit]]()
    private[this] val commit = commitSingleton
    contT.push2(RxnConsts.ContAfterPostCommit, RxnConsts.ContAndThen)

    private[this] var contTReset: Array[Byte] = contT.takeSnapshot()
    private[this] var contKReset: ListObjStack.Lst[Any] = objStackWithOneCommit

    private[this] var a: Any =
      x

    private[this] var retries: Int =
      0

    /** How many times was `desc` revalidated and successfully extended? */
    private[this] var descExtensions: Int =
      0

    /** Initially `true`, and if an MCAS cycle is detected, becomes `false` (and then remains `false`) */
    private[this] var optimisticMcas: Boolean =
      true

    /** Initially `true`, and if a `+` is encountered, becomes `false` (and then remains `false`) */
    private[this] var mutable: Boolean =
      true

    // TODO: this makes it slower if there is `+`! (See `InterpreterBench`.)

    @tailrec
    private[this] final def contKList: ListObjStack[Any] = {
      if (this.mutable) {
        this.convertToImmutable()
        this.contKList
      } else {
        this.contK.asInstanceOf[ListObjStack[Any]]
      }
    }

    /**
     * Used by `Read`/`TicketWrite` as an "out" parameter
     *
     * @see `entryPresent`/`entryAbsent`
     */
    private[this] var _entryHolder: LogEntry[Any] =
      null

    final override def entryAbsent(ref: MemoryLocation[Any], curr: Rxn[Any, Any]): LogEntry[Any] = {
      val res: LogEntry[Any] = curr match {
        case _: RefGetAxn[_] =>
          this.ctx.readIntoHwd(ref)
        case c: Upd[_, _, _] =>
          val hwd = this.ctx.readIntoHwd(c.ref)
          if (this.desc.isValidHwd(hwd)) {
            val ox = hwd.nv
            val (nx, b) = c.f(ox, this.a)
            this.a = b
            hwd.withNv(nx).cast[Any]
          } else {
            hwd.cast[Any]
          }
        case c: TicketWrite[_] =>
          c.hwd.withNv(c.newest).cast[Any]
        case _ =>
          throw new AssertionError(s"unexpected Rxn: ${curr.getClass}: $curr")
      }
      this._entryHolder = res // can be null
      res
    }

    final override def entryPresent(ref: MemoryLocation[Any], hwd: LogEntry[Any], curr: Rxn[Any, Any]): LogEntry[Any] = {
      _assert(hwd ne null)
      val res: LogEntry[Any] = curr match {
        case _: RefGetAxn[_] =>
          hwd
        case c: Upd[_, _, _] =>
          val ox = hwd.nv
          val (nx, b) = c.asInstanceOf[Upd[Any, Any, Any]].f(ox, this.a)
          this.a = b
          hwd.withNv(nx)
        case c: TicketWrite[_] =>
          // NB: This throws if it was modified in the meantime.
          // NB: This doesn't need extra validation, as
          // NB: `tryMergeTicket` checks that they have the
          // NB: same version.
          hwd.tryMergeTicket(c.hwd.cast[Any], c.newest)
        case _ =>
          throw new AssertionError(s"unexpected Rxn: ${curr.getClass}: $curr")
      }
      this._entryHolder = res
      res
    }

    private[this] var _stats: ExStatMap =
      null

    private[this] final def stats: ExStatMap = {
      val s = this._stats
      if (s eq null) {
        val s2 = this.ctx.getStatisticsP().asInstanceOf[ExStatMap]
        this._stats = s2
        s2
      } else {
        s
      }
    }

    private[this] final def saveStats(): Unit = {
      this._stats match {
        case null =>
          ()
        case s =>
          this.ctx.setStatisticsP(s.asInstanceOf[Map[AnyRef, AnyRef]])
      }
    }

    private[this] var _exParams: Exchanger.Params =
      null

    private[this] final def exParams: Exchanger.Params = {
      val ep = this._exParams
      if (ep eq null) {
        // TODO: this is a hack
        val ep2 = (stats.getOrElse(Exchanger.paramsKey, null): Any) match {
          case null =>
            val p = Exchanger.params // volatile read
            _stats = (_stats.asInstanceOf[Map[AnyRef, AnyRef]] + (Exchanger.paramsKey -> p)).asInstanceOf[ExStatMap]
            p
          case p: Exchanger.Params =>
            p
          case something =>
            impossible(s"found ${something.getClass.getName} instead of Exchanger.Params")
        }
        this._exParams = ep2
        ep2
      } else {
        ep
      }
    }

    private[this] final def setContReset(): Unit = {
      contTReset = contT.takeSnapshot()
      // TODO: Due to the next line, if we have
      // TODO: post-commit actions, we're always
      // TODO: falling back to `ListObjStack`
      // TODO: (even if we have no `+`). This
      // TODO: probably could be avoided.
      contKReset = contKList.takeSnapshot()
    }

    private[this] final def resetConts(): Unit = {
      contT.loadSnapshot(this.contTReset)
      val ckr = this.contKReset
      if (this.mutable && (ckr eq objStackWithOneCommit)) {
        this.contK = mkInitialContK()
      } else {
        this.contKList.loadSnapshot(ckr)
      }
    }

    private[this] final def clearAlts(): Unit = {
      alts.clear()
    }

    private[this] final def saveAlt(k: Rxn[Any, R]): Unit = {
      _saveAlt(this.alts, k)
    }

    private[this] final def saveStmAlt(k: Rxn[Any, R]): Unit = {
      _assert(this.isStm)
      _saveAlt(this.stmAlts, k)
    }

    private[this] final def _saveAlt(alts: ArrayObjStack[Any], k: Rxn[Any, R]): Unit = {
      alts.push(takeLocalsSnapshot(this.locals))
      val descSnap = _desc match {
        case null =>
          null
        case _ =>
          ctx.snapshot(this.descImm)
      }
      alts.push3(descSnap, a, contT.takeSnapshot())
      alts.push3(contKList.takeSnapshot(), pc.takeSnapshot(), k)
    }

    private[this] final def takeLocalsSnapshot(locals: IdentityHashMap[InternalLocal, AnyRef]): AnyRef = {
      if (locals eq null) {
        null
      } else {
        val snapshot = new IdentityHashMap[InternalLocal, AnyRef](locals.size())
        locals.forEach(new java.util.function.BiConsumer[InternalLocal, AnyRef] {
          final override def accept(k: InternalLocal, v: AnyRef): Unit = {
            val ov = snapshot.put(k, k.takeSnapshot())
            _assert(ov eq null)
          }
        })
        snapshot
      }
    }

    private[this] final def loadLocalsSnapshot(snap: AnyRef): Unit = {
      if (snap eq null) {
        clearLocals()
      } else {
        val snapshot = snap.asInstanceOf[IdentityHashMap[InternalLocal, AnyRef]]
        val locals = this.locals match {
          case null => new IdentityHashMap[InternalLocal, AnyRef](snapshot.size())
          case locals => locals
        }
        locals.clear()
        snapshot.forEach(new java.util.function.BiConsumer[InternalLocal, AnyRef] {
          final override def accept(k: InternalLocal, v: AnyRef): Unit = {
            k.loadSnapshot(v)
            val ov = locals.put(k, null)
            _assert(ov eq null)
          }
        })
        this.locals = locals
      }
    }

    private[this] final def clearLocals(): Unit = {
      this.locals match {
        case null => ()
        case locals => locals.clear()
      }
    }

    private[this] final def tryLoadAlt(isPermanentFailure: Boolean): Rxn[Any, R] = {
      if (isPermanentFailure) {
        _assert(this.isStm)
        _tryLoadAlt(this.stmAlts)
      } else {
        _tryLoadAlt(this.alts)
      }
    }

    private[this] final def discardStmAlt(): Unit = {
      _assert(this.isStm)
      this.stmAlts.popAndDiscard(7)
    }

    private[this] final def _tryLoadAlt(alts: ArrayObjStack[Any]): Rxn[Any, R] = {
      if (alts.nonEmpty()) {
        val res = alts.pop().asInstanceOf[Rxn[Any, R]]
        this._loadRestOfAlt(alts)
        res
      } else {
        null
      }
    }

    private[this] final def maybeMergeDescForStm(newDesc: Descriptor): Descriptor = {
      if (this.isStm) {
        val discarded = _desc
        if ((discarded ne null) && discarded.nonEmpty) {
          Descriptor.mergeReadsInto(newDesc, discarded)
        } else {
          newDesc
        }
      } else {
        newDesc
      }
    }

    private[this] final def _loadRestOfAlt(alts: ArrayObjStack[Any]): Unit = {
      pc.loadSnapshot(alts.pop().asInstanceOf[ListObjStack.Lst[Rxn[Any, Unit]]])
      contKList.loadSnapshot(alts.pop().asInstanceOf[ListObjStack.Lst[Any]])
      contT.loadSnapshot(alts.pop().asInstanceOf[Array[Byte]])
      a = alts.pop()
      _desc = this.maybeMergeDescForStm(alts.pop().asInstanceOf[Descriptor])
      loadLocalsSnapshot(alts.pop().asInstanceOf[AnyRef])
    }

    private[this] final def loadAltFrom(msg: Exchanger.Msg): Rxn[Any, R] = {
      pc.loadSnapshot(msg.postCommit)
      contKList.loadSnapshot(msg.contK)
      contT.loadSnapshot(msg.contT)
      a = msg.value
      // TODO: write a test for this (exchange + STM)
      desc = this.maybeMergeDescForStm(msg.desc)
      next().asInstanceOf[Rxn[Any, R]]
    }

    private[this] final def popFinalResult(): Any = {
      val r = contK.pop()
      _assert(!equ(r, postCommitResultMarker))
      r
    }

    @tailrec
    private[this] final def next(): Rxn[Any, Any] = {
      val contK = this.contK
      (contT.pop() : @switch) match {
        case 0 => // ContAndThen
          contK.pop().asInstanceOf[Rxn[Any, Any]]
        case 1 => // ContAndAlso
          val savedA = a
          a = contK.pop()
          val res = contK.pop().asInstanceOf[Rxn[Any, Any]]
          contK.push(savedA)
          res
        case 2 => // ContAndAlsoJoin
          val savedA = contK.pop()
          a = (savedA, a)
          next()
        case 3 => // ContTailRecM
          val e = a.asInstanceOf[Either[Any, Any]]
          a = contK.peek()
          val f = contK.peekSecond().asInstanceOf[Any => Rxn[Any, Any]]
          e match {
            case Left(more) =>
              contT.push(RxnConsts.ContTailRecM)
              f(more)
            case Right(done) =>
              a = done
              contK.pop() // a
              contK.pop() // f
              next()
          }
        case 4 => // ContPostCommit
          val pcAction = contK.pop().asInstanceOf[Rxn[Any, Any]]
          clearAlts()
          setContReset()
          a = () : Any
          startA = () : Any
          startRxn = pcAction
          this.retries = 0
          clearDesc()
          pcAction
        case 5 => // ContAfterPostCommit
          val res = popFinalResult()
          _assert(contK.isEmpty() && contT.isEmpty())
          new Done(res)
        case 6 => // ContCommitPostCommit
          a = postCommitResultMarker : Any
          commit.asInstanceOf[Rxn[Any, Any]]
        case 7 => // ContUpdWith
          val ox = contK.pop()
          val ref = contK.pop().asInstanceOf[MemoryLocation[Any]]
          val (nx, res) = a.asInstanceOf[Tuple2[_, _]]
          val hwd = desc.getOrElseNull(ref)
          _assert(hwd ne null)
          if (equ(hwd.nv, ox)) {
            this.desc = this.desc.overwrite(hwd.withNv(nx))
            a = res
          } else {
            // TODO: "during" the updWith, we wrote to
            // TODO: the same ref; what to do?
            throw new UnsupportedOperationException("wrote during updWith")
          }
          next()
        case 8 => // ContAs
          a = contK.pop()
          next()
        case 9 => // ContProductR
          a = contK.pop()
          contK.pop().asInstanceOf[Rxn[Any, Any]]
        case 10 => // ContFlatMapF
          val n = contK.pop().asInstanceOf[Function1[Any, Rxn[Any, Any]]].apply(a)
          a = () : Any
          n
        case 11 => // ContFlatMap
          val n = contK.pop().asInstanceOf[Function1[Any, Rxn[Any, Any]]].apply(a)
          a = contK.pop()
          n
        case 12 => // ContMap
          val b = contK.pop().asInstanceOf[Function1[Any, Any]].apply(a)
          a = b
          next()
        case 13 => // ContMap2Right
          val savedA = a
          a = contK.pop()
          val n = contK.pop().asInstanceOf[Rxn[Any, Any]]
          contK.push(savedA)
          n
        case 14 => // ContMap2Func
          val leftRes = contK.pop()
          val rightRes = a
          val f = contK.pop().asInstanceOf[Function2[Any, Any, Any]]
          a = f(leftRes, rightRes)
          next()
        case 15 => // ContOrElse
          discardStmAlt()
          next()
        case ct => // mustn't happen
          throw new UnsupportedOperationException(
            s"Unknown contT: ${ct}"
          )
      }
    }

    private[this] final def retry(): Rxn[Any, Any] =
      this.retry(canSuspend = this.canSuspend, permanent = false, noDebug = false)

    private[this] final def retry(canSuspend: Boolean, permanent: Boolean): Rxn[Any, Any] =
      this.retry(canSuspend = canSuspend, permanent = permanent, noDebug = false)

    private[this] final def retry(canSuspend: Boolean, permanent: Boolean, noDebug: Boolean): Rxn[Any, Any] = {
      _assert((!permanent) || this.isStm)
      if (this.strategy.isDebug && (!noDebug)) {
        this.strategy match {
          case str @ ((_: RetryStrategy.Spin) | (_: RetryStrategy.StrategyFull)) =>
            impossible(s"$str returned isDebug == true")
          case stepper: RetryStrategy.Internal.Stepper[_] =>
            _assert(this.isStm)
            return new SuspendWithStepper(stepper, stepper.asyncF.delay { // scalafix:ok
              this.retry(canSuspend, permanent, noDebug = true)
            })
        }
      }
      val alt = tryLoadAlt(isPermanentFailure = permanent)
      if (alt ne null) {
        // we're not actually retrying,
        // just going to the other side
        // of a `+` (so we're not
        // incrementing `retries`):
        alt
      } else {
        // really retrying:
        val retriesNow = this.retries + 1
        this.retries = retriesNow
        // check abnormal conditions:
        val mr = this.maxRetries
        if ((mr >= 0) && ((retriesNow > mr) || (retriesNow == Integer.MAX_VALUE))) {
          // TODO: maybe we could represent "infinity" with MAX_VALUE instead of -1?
          throw new MaxRetriesReached(mr)
        } else {
          maybeCheckInterrupt(retriesNow)
        }
        // STM might still need these:
        val d = if (this.isStm) this._desc else null
        // restart everything:
        clearDesc()
        a = startA
        resetConts()
        pc.clear()
        clearLocals()
        backoffAndNext(
          retriesNow,
          canSuspend = canSuspend,
          suspendUntilChanged = permanent,
          desc = d,
        )
      }
    }

    private[this] final def backoffAndNext(
      retries: Int,
      canSuspend: Boolean,
      suspendUntilChanged: Boolean,
      desc: AbstractDescriptor,
    ): Rxn[Any, Any] = {
      if (!suspendUntilChanged) { // spin/cede/sleep
        val token = Backoff2.backoffStrTok(
          retries = retries,
          strategy = this.strategy,
          canSuspend = canSuspend,
        )
        if (Backoff2.spinIfPauseToken(token)) {
          // ok, spinning done, restart:
          this.startRxn
        } else {
          _assert(canSuspend)
          new SuspendUntilBackoff(token)
        }
      } else { // STM
        _assert(canSuspend && this.isStm)
        new SuspendUntilChanged(desc)
      }
    }

    /**
     * Occasionally check for thread interruption
     *
     * As a last resort, we occasionally check the interrupt
     * status of the current thread. This way, a non-lock-free
     * (i.e., buggy) `Rxn` in an infinite loop can still be
     * interrupted by `Thread#interrupt` (in which case it will
     * throw an `InterruptedException`).
     */
    private[this] final def maybeCheckInterrupt(retries: Int): Unit = {
      if ((retries % interruptCheckPeriod) == 0) {
        checkInterrupt()
      }
    }

    private[this] final def checkInterrupt(): Unit = {
      if (Thread.interrupted()) {
        throw new InterruptedException
      }
    }

    /**
     * Specialized variant of `MCAS.ThreadContext#readMaybeFromLog`.
     * Note: doesn't put a fresh HWD into the log!
     * Note: returns `null` if a rollback is required!
     * Note: may update `desc` (revalidate/extend).
     */
    private[this] final def readMaybeFromLog[A](ref: MemoryLocation[A]): LogEntry[A] = {
      desc.getOrElseNull(ref) match {
        case null =>
          // not in log
          revalidateIfNeeded(ctx.readIntoHwd(ref))
        case hwd =>
          hwd
      }
    }

    private[this] final def revalidateIfNeeded[A](hwd: LogEntry[A]): LogEntry[A] = {
      _assert(hwd ne null)
      if (!desc.isValidHwd(hwd)) {
        if (forceValidate(hwd)) {
          // OK, `desc` was extended
          hwd
        } else {
          // need to roll back
          null
        }
      } else {
        hwd
      }
    }

    private[this] final def forceValidate(optHwd: LogEntry[_]): Boolean = {
      ctx.validateAndTryExtend(desc, hwd = optHwd) match {
        case null =>
          // need to roll back
          clearDesc()
          false
        case newDesc =>
          // OK, it was extended
          this.descExtensions += 1
          desc = newDesc
          true
      }
    }

    private[this] final def performMcas(d: AbstractDescriptor): Boolean = {
      if (d ne null) {
        val o = if (this.optimisticMcas) Consts.OPTIMISTIC else Consts.PESSIMISTIC
        val success = ctx.tryPerform(d, o) match {
          case McasStatus.Successful =>
            true
          case Version.Reserved =>
            // a cycle was detected
            this.optimisticMcas = false
            false
          case _ =>
            false
        }
        // `Succesful` is success; otherwise the result is:
        // - Either `McasStatus.FailedVal`, which means that
        //   (at least) one word had an unexpected value
        //   (so we can't commit), or unexpected version (so
        //    revalidation would vertainly fail).
        // - Or `Version.Reserved`, which is essentially the
        //   same, but also hints that we should be pessimistic
        //   in the future.
        // - Or it's a new global version, which means that
        //   the global version CAS failed, in which case
        //   we COULD try to `validateAndTryExtend` the
        //   descriptor, and retry only the MCAS (as opposed
        //   to the whole `Rxn`). BUT this never happens with
        //   any of the "proper" MCAS implementations: `Emcas`
        //   handles the global version in a smarter way, so
        //   never has a "global version CAS", and on JS (with
        //   `ThreadConfinedMCAS`) the global version CAS can
        //   never fail (due to being single-threaded). So in
        //   this case (with "improper" MCAS impls) we can also
        //   just return `false`; this is not a correctness
        //   problem, but only a performance issue (but we
        //   don't really care about the performance of "improper"
        //   MCASes anyway).
        success
      } else {
        true
      }
    }

    @tailrec
    private[this] final def loop[A, B](curr: Rxn[A, B]): R = {
      // TODO: While doing the runloop, we could
      // TODO: periodically (how often?) check the
      // TODO: global (EMCAS) version number. If it
      // TODO: changed (that means someone committed),
      // TODO: it _may_ be worth it to revalidate our
      // TODO: log. If it's invalid, we should `retry`
      // TODO: immediately (i.e., abandon our current
      // TODO: progress, because it's impossible to
      // TODO: commit it anyway); otherwise it was a
      // TODO: non-conflicting commit, so we can continue.
      // TODO: (We should benchmark this change with
      // TODO: something with long transactions.)
      (curr.tag : @switch) match {
        case 0 => // Commit
          val d = this._desc // we avoid calling `desc` here, in case it's `null`
          this.clearDesc()
          val dSize = if (d ne null) d.size else 0
          if (performMcas(d)) {
            if (Consts.statsEnabled) {
              // save retry statistics:
              ctx.recordCommit(retries = this.retries, committedRefs = dSize, descExtensions = this.descExtensions)
            }
            // ok, commit is done, but we still need to perform post-commit actions
            val res = a
            a = () : Any
            if (!equ(res, postCommitResultMarker)) {
              // final result, Done will need it:
              contK.push(res)
            }
            while (pc.nonEmpty()) {
              contT.push2(
                RxnConsts.ContCommitPostCommit, // commits the post-commit action
                RxnConsts.ContPostCommit, // the post-commit action itself
              )
              contK.push(pc.pop())
            }
            loop(next())
          } else {
            contK.push(commit)
            contT.push(RxnConsts.ContAndThen)
            loop(retry())
          }
        case 1 => // AlwaysRetry
          loop(retry())
        case 2 => // PostCommit
          val c = curr.asInstanceOf[PostCommit[A]]
          pc.push(c.pc.provide(a.asInstanceOf[A]))
          loop(next())
        case 3 => // Lift
          val c = curr.asInstanceOf[Lift[A, B]]
          a = c.func(a.asInstanceOf[A])
          loop(next())
        case 4 => // Computed
          val c = curr.asInstanceOf[Computed[A, B]]
          val nxt = c.f(a.asInstanceOf[A])
          a = () : Any
          loop(nxt)
        case 5 => // RetryWhenChanged (STM)
          _assert(this.canSuspend && this.isStm)
          loop(retry(canSuspend = true, permanent = true))
        case 6 => // Choice
          val c = curr.asInstanceOf[Choice[Any, R]]
          saveAlt(c.right)
          loop(c.left)
        case 7 => // Cas
          val c = curr.asInstanceOf[Cas[Any]]
          val hwd = readMaybeFromLog(c.ref)
          if (hwd eq null) {
            loop(retry())
          } else {
            val currVal = hwd.nv
            if (equ(currVal, c.ov)) {
              desc = desc.addOrOverwrite(hwd.withNv(c.nv))
              a = () : Unit
              loop(next())
            }
            else {
              loop(retry())
            }
          }
        case 8 => // RefGetAxn
          val ref = curr.asInstanceOf[MemoryLocation[Any] with Rxn[Any, Any]]
          _assert(this._entryHolder eq null) // just to be sure
          desc = desc.computeIfAbsent(ref, tok = ref, visitor = this)
          val hwd = this._entryHolder
          this._entryHolder = null // cleanup
          val hwd2 = revalidateIfNeeded(hwd)
          if (hwd2 eq null) {
            _assert(this._desc eq null)
            loop(retry())
          } else {
            a = hwd2.nv
            loop(next())
          }
        case 9 => // Map2
          val c = curr.asInstanceOf[Map2[_, _, _, _]]
          contT.push2(RxnConsts.ContMap2Func, RxnConsts.ContMap2Right)
          contK.push3(c.f, c.right, a)
          loop(c.left)
        case 10 => // Upd
          val c = curr.asInstanceOf[Upd[A, B, Any]]
          _assert(this._entryHolder eq null) // just to be sure
          desc = desc.computeOrModify(c.ref, tok = curr.asInstanceOf[Rxn[Any, Any]], visitor = this)
          val hwd = this._entryHolder
          this._entryHolder = null // cleanup
          val nxt = if (!desc.isValidHwd(hwd)) {
            if (forceValidate(hwd)) {
              // OK, `desc` was extended;
              // but need to finish `Upd`:
              val ox = hwd.nv
              val (nx, b) = c.f(ox, this.a.asInstanceOf[A])
              this.a = b
              desc = desc.overwrite(hwd.withNv(nx).cast[Any])
              next()
            } else {
              _assert(this._desc eq null)
              retry()
            }
          } else {
            next()
          }
          loop(nxt)
        case 11 => // TicketWrite
          val c = curr.asInstanceOf[TicketWrite[Any]]
          _assert(this._entryHolder eq null) // just to be sure
          a = () : Any
          desc = desc.computeOrModify(c.hwd.address, tok = c, visitor = this)
          val newHwd = this._entryHolder
          this._entryHolder = null // cleanup
          val newHwd2 = revalidateIfNeeded(newHwd)
          if (newHwd2 eq null) {
            _assert(this._desc eq null)
            loop(retry())
          } else {
            loop(next())
          }
        case 12 => // DirectRead
          val c = curr.asInstanceOf[DirectRead[B]]
          a = ctx.readDirect(c.ref)
          loop(next())
        case 13 => // Exchange
          val c = curr.asInstanceOf[Exchange[A, B]]
          val msg = Exchanger.Msg(
            value = a,
            contK = contKList.takeSnapshot(),
            contT = contT.takeSnapshot(),
            desc = this.descImm, // TODO: could we just call `toImmutable`?
            postCommit = pc.takeSnapshot(),
            exchangerData = stats,
          )
          c.exchanger.tryExchange(msg = msg, params = exParams, ctx = ctx) match {
            case Left(newStats) =>
              _stats = newStats
              // Couldn't exchange, because:
              // - didn't find a partner in time; or
              // - found a partner, but it didn't fulfilled our offer in time; or
              // - found a partner, but it rescinded before we could've fulfilled its offer; or
              // - found a partner, but the merged descriptor can't be extended; or
              // - found a partner with an overlapping descriptor.
              // In any case we'll retry (since `Exchanger` is supposed to be
              // used through `Eliminator`, we'll probably retry the primary op).
              loop(retry())
            case Right(contMsg) =>
              _stats = contMsg.exchangerData
              loop(loadAltFrom(contMsg))
          }
        case 14 => // AndThen
          val c = curr.asInstanceOf[AndThen[A, _, B]]
          contT.push(RxnConsts.ContAndThen)
          contK.push(c.right)
          loop(c.left)
        case 15 => // AndAlso
          val c = curr.asInstanceOf[AndAlso[_, _, _, _]]
          val xp = a.asInstanceOf[Tuple2[_, _]]
          contT.push2(RxnConsts.ContAndAlsoJoin, RxnConsts.ContAndAlso)
          contK.push2(c.right, xp._2)
          // left:
          a = xp._1
          loop(c.left)
        case 16 => // Done
          val c = curr.asInstanceOf[Done[R]]
          c.result
        case 17 => // Ctx
          val c = curr.asInstanceOf[Ctx[R]]
          a = c.uf(ctx)
          loop(next())
        case 18 => // Provide
          val c = curr.asInstanceOf[Provide[A, B]]
          a = c.a
          loop(c.rxn)
        case 19 => // UpdWith
          val c = curr.asInstanceOf[UpdWith[Any, Any, _]]
          val hwd = readMaybeFromLog(c.ref)
          if (hwd eq null) {
            loop(retry())
          } else {
            val ox = hwd.nv
            val axn = c.f(ox, a)
            desc = desc.addOrOverwrite(hwd)
            contT.push(RxnConsts.ContUpdWith)
            contK.push2(c.ref, ox)
            // TODO: if `axn` writes to the same ref, we'll throw (see above)
            loop(axn)
          }
        case 20 => // As
          val c = curr.asInstanceOf[As[_, _, _]]
          contT.push(RxnConsts.ContAs)
          contK.push(c.c)
          loop(c.rxn)
        case 21 => // FinishExchange
          val c = curr.asInstanceOf[FinishExchange[Any]]
          val currentContT = contT.takeSnapshot()
          //println(s"FinishExchange: currentContT = '${java.util.Arrays.toString(currentContT)}' - thread#${Thread.currentThread().getId()}")
          val (newContT, _otherContT) = ByteStack.splitAt(currentContT, idx = c.lenSelfContT)
          contT.loadSnapshot(newContT)
          // Ugh...
          // the exchanger (correctly) leaves the Commit() in otherContK;
          // however, the extraOp (this FinishExchange) already "ate"
          // the ContAndThen from otherContT which belongs to that Commit();
          // so we push back that ContAndThen here:
          val otherContT = ByteStack.push(_otherContT, RxnConsts.ContAndThen)
          //println(s"FinishExchange: passing back result '${a}' - thread#${Thread.currentThread().getId()}")
          //println(s"FinishExchange: passing back contT ${java.util.Arrays.toString(otherContT)} - thread#${Thread.currentThread().getId()}")
          //println(s"FinishExchange: passing back contK ${c.restOtherContK.mkString()} - thread#${Thread.currentThread().getId()}")
          val fx = new Exchanger.FinishedEx[Any](
            result = a,
            contK = c.restOtherContK,
            contT = otherContT,
          )
          desc = ctx.addCasFromInitial(desc, c.hole.loc, null, fx)
          a = contK.pop() // the exchanged value we've got from the other thread
          //println(s"FinishExchange: our result is '${a}' - thread#${Thread.currentThread().getId()}")
          loop(next())
        case 22 => // TicketRead
          val c = curr.asInstanceOf[TicketRead[Any]]
          val hwd = readMaybeFromLog(c.ref)
          if (hwd eq null) {
            loop(retry())
          } else {
            a = new unsafe.TicketImpl[Any](hwd)
            loop(next())
          }
        case 23 => // ForceValidate
          if (forceValidate(optHwd = null)) {
            a = () : Any
            loop(next())
          } else {
            loop(retry())
          }
        case 24 => // Pure
          val c = curr.asInstanceOf[Pure[Any]]
          a = c.a
          loop(next())
        case 25 => // ProductR
          val c = curr.asInstanceOf[ProductR[Any, Any, Any]]
          contT.push(RxnConsts.ContProductR)
          contK.push2(c.right, a)
          loop(c.left)
        case 26 => // FlatMapF
          val c = curr.asInstanceOf[FlatMapF[Any, Any, Any]]
          contT.push(RxnConsts.ContFlatMapF)
          contK.push(c.f)
          loop(c.rxn)
        case 27 => // FlatMap
          val c = curr.asInstanceOf[FlatMap[Any, Any, Any]]
          contT.push(RxnConsts.ContFlatMap)
          contK.push2(a, c.f)
          loop(c.rxn)
        case 28 => // SuspendUntil
          _assert(this.canSuspend)
          // user code can't access a `SuspendUntil`, so
          // we can abuse `R` and return `SuspendUntil`:
          curr.asInstanceOf[R]
        case 29 => // TailRecM
          val c = curr.asInstanceOf[TailRecM[Any, Any, Any]]
          val f = c.f
          val nxt = f(c.a)
          contT.push(RxnConsts.ContTailRecM)
          contK.push2(f, a)
          loop(nxt)
        case 30 => // Map_
          val c = curr.asInstanceOf[Map_[Any, Any, Any]]
          contT.push(RxnConsts.ContMap)
          contK.push(c.f)
          loop(c.rxn)
        case 31 => // OrElse (STM)
          val c = curr.asInstanceOf[OrElse[Any, R]]
          saveStmAlt(c.right)
          contT.push(RxnConsts.ContOrElse)
          loop(c.left)
        case 32 => // Unread
          if ((_desc ne null) && desc.nonEmpty) {
            val c = curr.asInstanceOf[Unread[_]]
            val loc = c.ref.loc
            desc = desc.removeReadOnlyRef(loc) // throws if not RO ref; NOP if it doesn't contain the ref
          } // else: empty log, nothing to do
          a = ()
          loop(next())
        case 33 => // LocalNewEnd
          val c = curr.asInstanceOf[LocalNewEnd]
          val locals = this.locals match {
            case null =>
              val nl = new IdentityHashMap[InternalLocal, AnyRef]
              this.locals = nl
              nl
            case locals =>
              locals
          }
          if (!c.isEnd) {
            locals.put(c.local, null)
          } else {
            val ok = locals.remove(c.local, null)
            _assert(ok)
          }
          loop(next())
        case t => // mustn't happen
          impossible(s"Unknown tag ${t} for ${curr}")
      }
    }

    final def interpretAsync[F[_]](poll: F ~> F)(implicit F: Async[F]): F[R] = {
      if (this.canSuspend) {
        // cede or sleep strategy:
        def step(ctxHint: Mcas.ThreadContext, debugNext: Rxn[Any, Any]): F[R] = F.defer {
          val ctx = if ((ctxHint ne null) && mcas.isCurrentContext(ctxHint)) {
            ctxHint
          } else {
            mcas.currentContext()
          }
          this.ctx = ctx
          try {
            loop(if (debugNext eq null) startRxn else debugNext) match {
              case s: SuspendUntil =>
                _assert(this._entryHolder eq null)
                val sus: F[Rxn[Any, Any]] = s.toF[F](mcas, ctx)
                F.flatMap(poll(sus)) { nxt => step(ctxHint = ctx, debugNext = nxt) }
              case r =>
                _assert(
                  (this._entryHolder eq null) &&
                  ((this.locals eq null) || this.locals.isEmpty())
                )
                F.pure(r)
            }
          } finally {
            this.saveStats()
            this.invalidateCtx()
          }
        }
        step(ctxHint = null, debugNext = null)
      } else {
        // spin strategy, so not really async:
        F.delay {
          this.interpretSync()
        }
      }
    }

    final def interpretSync(): R = {
      interpretSyncWithContext(mcas.currentContext())
    }

    /** This is also called for tests/benchmarks by `unsafePerformInternal` above. */
    final def interpretSyncWithContext(ctx: Mcas.ThreadContext): R = {
      _assert(!canSuspend)
      this.ctx = ctx
      try {
        val r = loop(startRxn)
        _assert(this._entryHolder eq null)
        r
      } finally {
        this.saveStats()
        this.invalidateCtx()
      }
    }
  }
}

private[core] sealed abstract class RxnInstances0 extends RxnInstances1 { this: Rxn.type =>

  implicit final def arrowChoiceInstance: ArrowChoice[Rxn] =
    _arrowChoiceInstance

  private[this] val _arrowChoiceInstance: ArrowChoice[Rxn] = new ArrowChoice[Rxn] {

    final override def compose[A, B, C](f: Rxn[B, C], g: Rxn[A, B]): Rxn[A, C] =
      g >>> f

    final override def first[A, B, C](fa: Rxn[A, B]): Rxn[(A, C), (B, C)] =
      fa.first[C]

    final override def second[A, B, C](fa: Rxn[A, B]): Rxn[(C, A), (C, B)] =
      fa.second[C]

    final override def lift[A, B](f: A => B): Rxn[A, B] =
      Rxn.lift(f)

    final override def choose[A, B, C, D](f: Rxn[A, C])(g: Rxn[B, D]): Rxn[Either[A, B], Either[C, D]] = {
      computed[Either[A, B], Either[C, D]] {
        case Left(a) => (pure(a) >>> f).map(Left(_))
        case Right(b) => (pure(b) >>> g).map(Right(_))
      }
    }

    final override def id[A]: Rxn[A, A] =
      identity[A]

    final override def choice[A, B, C](f: Rxn[A, C], g: Rxn[B, C]): Rxn[Either[A, B], C] = {
      computed[Either[A, B], C] {
        case Left(a) => pure(a) >>> f
        case Right(b) => pure(b) >>> g
      }
    }

    final override def lmap[A, B, X](fa: Rxn[A, B])(f: X => A): Rxn[X, B] =
      fa.contramap(f)

    final override def rmap[A, B, C](fa: Rxn[A, B])(f: B => C): Rxn[A, C] =
      fa.map(f)
  }
}

private sealed abstract class RxnInstances1 extends RxnInstances2 { self: Rxn.type =>

  implicit final def localInstance[E]: Local[Rxn[E, *], E] =
    _localInstance.asInstanceOf[Local[Rxn[E, *], E]]

  private[this] val _localInstance: Local[Rxn[Any, *], Any] = new Local[Rxn[Any, *], Any] {
    final override def applicative: Applicative[Rxn[Any, *]] =
      self.monadInstance[Any]
    final override def ask[E2 >: Any]: Rxn[Any, E2] =
      Rxn.identity[Any]
    final override def local[A](fa: Rxn[Any, A])(f: Any => Any): Rxn[Any, A] =
      fa.contramap(f)
  }
}

private sealed abstract class RxnInstances2 extends RxnInstances3 { this: Rxn.type =>

  // Even though we override `tailRecM`, we still
  // inherit `StackSafeMonad`, in case someone
  // somewhere uses that as a marker or even a
  // typeclass:
  implicit final def monadInstance[X]: StackSafeMonad[Rxn[X, *]] =
    _monadInstance.asInstanceOf[StackSafeMonad[Rxn[X, *]]]

  private[this] val _monadInstance: StackSafeMonad[Rxn[Any, *]] = new StackSafeMonad[Rxn[Any, *]] {
    final override def unit: Rxn[Any, Unit] =
      Rxn.unit
    final override def pure[A](a: A): Rxn[Any, A] =
      Rxn.pure(a)
    final override def point[A](a: A): Rxn[Any, A] =
      Rxn.pure(a)
    final override def as[A, B](fa: Rxn[Any, A], b: B): Rxn[Any, B] =
      fa.as(b)
    final override def void[A](fa: Rxn[Any, A]): Rxn[Any, Unit] =
      fa.void
    final override def map[A, B](fa: Rxn[Any, A])(f: A => B): Rxn[Any, B] =
      fa.map(f)
    final override def map2[A, B, Z](fa: Rxn[Any, A], fb: Rxn[Any, B])(f: (A, B) => Z): Rxn[Any, Z] =
      fa.map2(fb)(f)
    final override def productR[A, B](fa: Rxn[Any, A])(fb: Rxn[Any, B]): Rxn[Any, B] =
      fa.productR(fb)
    final override def product[A, B](fa: Rxn[Any, A], fb: Rxn[Any, B]): Rxn[Any, (A, B)] =
      fa product fb
    final override def flatMap[A, B](fa: Rxn[Any, A])(f: A => Rxn[Any, B]): Rxn[Any, B] =
      fa.flatMap(f)
    final override def tailRecM[A, B](a: A)(f: A => Rxn[Any, Either[A, B]]): Rxn[Any, B] =
      Rxn.tailRecM[Any, A, B](a)(f)
  }
}

private sealed abstract class RxnInstances3 extends RxnInstances4 { self: Rxn.type =>

  implicit final def uniqueInstance[X]: Unique[Rxn[X, *]] =
    _uniqueInstance.asInstanceOf[Unique[Rxn[X, *]]]

  private[this] val _uniqueInstance: Unique[Rxn[Any, *]] = new Unique[Rxn[Any, *]] {
    final override def applicative: Applicative[Rxn[Any, *]] =
      self.monadInstance[Any]
    final override def unique: Rxn[Any, Unique.Token] =
      self.unique
  }
}

private sealed abstract class RxnInstances4 extends RxnInstances5 { this: Rxn.type =>

  implicit final def monoidKInstance: MonoidK[λ[a => Rxn[a, a]]] =
    _monoidKInstance

  private[this] val _monoidKInstance: MonoidK[λ[a => Rxn[a, a]]] = {
    new MonoidK[λ[a => Rxn[a, a]]] {
      final override def combineK[A](x: Rxn[A, A], y: Rxn[A, A]): Rxn[A, A] =
        x >>> y
      final override def empty[A]: Rxn[A, A] =
        Rxn.identity[A]
    }
  }
}

private sealed abstract class RxnInstances5 extends RxnInstances6 { this: Rxn.type =>

  /** Not implicit, because it would conflict with [[monoidInstance]]. */
  final def choiceSemigroup[A, B]: Semigroup[Rxn[A, B]] =
    _choiceSemigroup.asInstanceOf[Semigroup[Rxn[A, B]]]

  private[this] val _choiceSemigroup: Semigroup[Rxn[Any, Any]] = new Semigroup[Rxn[Any, Any]] {
    final override def combine(x: Rxn[Any, Any], y: Rxn[Any, Any]): Rxn[Any, Any] =
      x + y
  }

  implicit final def monoidInstance[A, B](implicit B: Monoid[B]): Monoid[Rxn[A, B]] = new Monoid[Rxn[A, B]] {
    final override def combine(x: Rxn[A, B], y: Rxn[A, B]): Rxn[A, B] =
      x.map2(y) { (b1, b2) => B.combine(b1, b2) }
    final override def empty: Rxn[A, B] =
      Rxn.pure(B.empty)
  }
}

private sealed abstract class RxnInstances6 extends RxnInstances7 { self: Rxn.type =>

  implicit final def deferInstance[X]: Defer[Rxn[X, *]] =
    _deferInstance.asInstanceOf[Defer[Rxn[X, *]]]

  private[this] val _deferInstance: Defer[Rxn[Any, *]] = new Defer[Rxn[Any, *]] {
    final override def defer[A](fa: => Rxn[Any, A]): Rxn[Any, A] =
      self.computed[Any, A] { x => fa.provide(x) }
    final override def fix[A](fn: Rxn[Any, A] => Rxn[Any, A]): Rxn[Any, A] = {
      // Instead of a `lazy val` (like in the superclass), we just
      // do a rel/acq here, because we know exactly how `defer`
      // works, and know that `.elem` will be initialized before
      // we return from this method. However, we still need the
      // fences, to make sure that if the resulting `Rxn[X, A]`
      // is published by a race, there is an ordering between
      // writing `ref.elem` and reading it.
      // TODO: The point of this whole thing is to avoid a
      // TODO: `lazy val`, which might block. This way of
      // TODO: doing it is correct, but it's unclear if it's
      // TODO: faster than a `lazy val`, and also, it could
      // TODO: be that in this specific case, a `lazy val`
      // TODO: also woudn't block.
      val ref = new scala.runtime.ObjectRef[Rxn[Any, A]](null)
      ref.elem = fn(defer {
        self.acquireFence()
        ref.elem
      })
      self.releaseFence()
      ref.elem
    }
  }
}

private sealed abstract class RxnInstances7 extends RxnInstances8 { self: Rxn.type =>

  implicit final def showInstance[A, B]: Show[Rxn[A, B]] =
    _showInstance.asInstanceOf[Show[Rxn[A, B]]]

  private[this] val _showInstance: Show[Rxn[Any, Any]] = new Show[Rxn[Any, Any]] {
    final override def show(rxn: Rxn[Any, Any]): String = rxn match {
      case rg: RefGetAxn[_] =>
        // this would have the .toString of a Ref, so we're cheating:
        s"RefGetAxn(${rg})"
      case _ =>
        // all the others have a proper .toString:
        rxn.toString
    }
  }
}

private sealed abstract class RxnInstances8 extends RxnInstances9 { self: Rxn.type =>

  implicit final def alignInstance[X]: Align[Rxn[X, *]] =
    _alignInstance.asInstanceOf[Align[Rxn[X, *]]]

  private[this] val _alignInstance: Align[Rxn[Any, *]] = new Align[Rxn[Any, *]] {
    final override def functor: Functor[Rxn[Any, *]] =
      self.monadInstance[Any]
    final override def align[A, B](fa: Rxn[Any, A], fb: Rxn[Any, B]): Rxn[Any, Ior[A, B]] = {
      val leftOrBoth = (fa * fb.?).map {
        case (a, Some(b)) => Ior.both(a, b)
        case (a, None) => Ior.left(a)
      }
      val right = fb.map(Ior.right)
      leftOrBoth + right
    }
  }
}

private sealed abstract class RxnInstances9 extends RxnInstances10 { self: Rxn.type =>

  implicit final def uuidGenInstance[X]: UUIDGen[Rxn[X, *]] =
    self._uuidGen.asInstanceOf[UUIDGen[Rxn[X, *]]]

  private[this] val _uuidGen: UUIDGen[Rxn[Any, *]] = new UUIDGen[Rxn[Any, *]] {
    final override def randomUUID: Rxn[Any, UUID] =
      newUuidImpl
  }
}

private sealed abstract class RxnInstances10 extends RxnInstances11 { self: Rxn.type =>

  import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS, MILLISECONDS }

  implicit final def clockInstance[X]: Clock[Rxn[X, *]] =
    _clockInstance.asInstanceOf[Clock[Rxn[X, *]]]

  private[this] val _clockInstance: Clock[Rxn[Any, *]] = new Clock[Rxn[Any, *]] {
    final override def applicative: Applicative[Rxn[Any, *]] =
      Rxn.monadInstance[Any]
    final override def monotonic: Rxn[Any, FiniteDuration] =
      Axn.unsafe.delay { FiniteDuration(System.nanoTime(), NANOSECONDS) }
    final override def realTime: Rxn[Any, FiniteDuration] =
      Axn.unsafe.delay { FiniteDuration(System.currentTimeMillis(), MILLISECONDS) }
  }
}

private sealed abstract class RxnInstances11 extends RxnSyntax0 { self: Rxn.type =>

  implicit final def catsRefMakeInstance[X]: CatsRef.Make[Rxn[X, *]] =
    _catsRefMakeInstance.asInstanceOf[CatsRef.Make[Rxn[X, *]]]

  private[this] val _catsRefMakeInstance: CatsRef.Make[Rxn[Any, *]] = new CatsRef.Make[Rxn[Any, *]] {
    final override def refOf[A](a: A): Rxn[Any, CatsRef[Rxn[Any, *], A]] = {
      refs.Ref.unpadded(initial = a).map { underlying =>
        new CatsRef[Rxn[Any, *], A] {
          final override def get: Rxn[Any, A] =
            underlying.get
          final override def set(a: A): Rxn[Any, Unit] =
            underlying.set1(a)
          final override def access: Rxn[Any, (A, A => Rxn[Any, Boolean])] = {
            underlying.get.map { ov =>
              val setter = { (nv: A) =>
                // TODO: can we relax this? Would `ticketRead` be safe?
                underlying.modify { cv => if (equ(cv, ov)) (nv, true) else (cv, false) }
              }
              (ov, setter)
            }
          }
          final override def tryUpdate(f: A => A): Rxn[Any, Boolean] =
            this.update(f).maybe
          final override def tryModify[B](f: A => (A, B)): Rxn[Any, Option[B]] =
            this.modify(f).attempt
          final override def update(f: A => A): Rxn[Any, Unit] =
            underlying.update(f)
          final override def modify[B](f: A => (A, B)): Rxn[Any, B] =
            underlying.modify(f)
          final override def tryModifyState[B](state: State[A, B]): Rxn[Any, Option[B]] =
            underlying.tryModify { a => state.runF.flatMap(_(a)).value }
          final override def modifyState[B](state: State[A, B]): Rxn[Any, B] =
            underlying.modify { a => state.runF.flatMap(_(a)).value }
        }
      }
    }
  }
}

private sealed abstract class RxnSyntax0 extends RxnSyntax1 { this: Rxn.type =>

  import scala.language.implicitConversions

  implicit final def rxnInvariantSyntax[A, B](self: Rxn[A, B]): Rxn.InvariantSyntax[A, B] =
    new Rxn.InvariantSyntax(self)
}

private sealed abstract class RxnSyntax1 extends RxnSyntax2 { this: Rxn.type =>

  import scala.language.implicitConversions

  implicit final def rxnAxnSyntax[A](self: Axn[A]): Rxn.AxnSyntax[A] =
    new Rxn.AxnSyntax(self)
}

private sealed abstract class RxnSyntax2 extends RxnCompanionPlatform { this: Rxn.type =>

  import scala.language.implicitConversions

  implicit final def rxnTuple2RxnSyntax[A, B, C](self: Rxn[A, (B, C)]): Rxn.Tuple2RxnSyntax[A, B, C] =
    new Rxn.Tuple2RxnSyntax(self)
}
