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

import scala.util.control.NonFatal

import cats.{ ~>, Align, Applicative, Defer, Functor, StackSafeMonad, Monoid, Semigroup, Show }
import cats.data.{ Ior, State, NonEmptyList }
import cats.effect.kernel.{ Async, Clock, Cont, Unique, MonadCancel, Ref => CatsRef }
import cats.effect.std.{ Random, SecureRandom, UUIDGen }

import dev.tauri.choam.{ unsafe => unsafe2 }
import stm.Txn
import internal.mcas.{ MemoryLocation, Mcas, LogEntry, McasStatus, Descriptor, AbstractDescriptor, Consts, Hamt, Version }
import internal.mcas.Hamt.IllegalInsertException
import internal.random

/**
 * A effect with result type `B`; when executed, it
 * may update any number of [[Ref]]s atomically. (It
 * may also create new [[Ref]]s.)
 *
 * These functions are composable (see below), and composition
 * preserves their atomicity. That is, all affected [[Ref]]s
 * will be updated atomically.
 *
 * A [[Rxn]] forms a [[cats.Monad Monad]], so the usual
 * monadic combinators can be used to compose `Rxn`s.
 */
sealed abstract class Rxn[+B] { // short for 'reaction'

  /*
   * An implementation inspired by reagents, described in [Reagents: Expressing and
   * Composing Fine-grained Concurrency](https://www.ccs.neu.edu/home/turon/reagents.pdf)
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
   * reads and writes to the same `Ref` in one `Rxn`.
   */

  def + [Y >: B](that: Rxn[Y]): Rxn[Y]

  def * [C](that: Rxn[C]): Rxn[(B, C)]

  def product[C](that: Rxn[C]): Rxn[(B, C)]

  def ? : Rxn[Option[B]]

  def attempt: Rxn[Option[B]]

  def maybe: Rxn[Boolean]

  def map[C](f: B => C): Rxn[C]

  def as[C](c: C): Rxn[C]

  def void: Rxn[Unit]

  def map2[C, D](that: Rxn[C])(f: (B, C) => D): Rxn[D]

  def <* [C](that: Rxn[C]): Rxn[B]

  def productL [C](that: Rxn[C]): Rxn[B]

  def *> [C](that: Rxn[C]): Rxn[C]

  def productR[C](that: Rxn[C]): Rxn[C]

  def flatMap[C](f: B => Rxn[C]): Rxn[C]

  def >> [C](that: => Rxn[C]): Rxn[C]

  def flatTap(f: B => Rxn[Unit]): Rxn[B]

  def flatten[C](implicit ev: B <:< Rxn[C]): Rxn[C]

  def postCommit(pc: B => Rxn[Unit]): Rxn[B]

  final def postCommit(pc: Rxn[Unit]): Rxn[B] =
    this.postCommit { _ => pc }

  /**
   * Execute the [[Rxn]].
   *
   * This method is `unsafe` because it performs side-effects.
   *
   * @param rt the [[ChoamRuntime]] which will run the [[Rxn]].
   * @return the result of the executed [[Rxn]].
   */
  final def unsafePerform(
    rt: ChoamRuntime,
  ): B = this.unsafePerform(rt.mcasImpl, RetryStrategy.Default)

  /**
   * Execute the [[Rxn]].
   *
   * This method is `unsafe` because it performs side-effects.
   *
   * @param rt the [[ChoamRuntime]] which will run the [[Rxn]].
   * @param strategy the retry strategy to use.
   * @return the result of the executed [[Rxn]].
   */
  final def unsafePerform(
    rt: ChoamRuntime,
    strategy: RetryStrategy.Spin,
  ): B = this.unsafePerform(rt.mcasImpl, strategy)

  private[choam] final def unsafePerform(
    mcas: Mcas,
    strategy: RetryStrategy.Spin = RetryStrategy.Default,
  ): B = {
    new Rxn.InterpreterState[B](
      rxn = this,
      mcas = mcas,
      strategy = strategy,
      isStm = false,
    ).interpretSync()
  }

  private[choam] final def perform[F[_], X >: B](
    rt: ChoamRuntime,
    strategy: RetryStrategy = RetryStrategy.Default,
  )(implicit F: Async[F]): F[X] = this.performInternal(rt.mcasImpl, strategy)

  private[choam] final def performInternal[F[_], X >: B](
    mcas: Mcas,
    strategy: RetryStrategy = RetryStrategy.Default,
  )(implicit F: Async[F]): F[X] = {
    // It is unsafe to accept a `Stepper` through
    // this method, since it could be a `Stepper[G]`,
    // where `G` is different form `F`:
    require(!strategy.isDebug)
    performInternal0[F, X](mcas = mcas, strategy = strategy)
  }

  private[choam] final def performWithStepper[F[_], X >: B](
    mcas: Mcas,
    stepper: RetryStrategy.Internal.Stepper[F],
  )(implicit F: Async[F]): F[X] = {
    performInternal0[F, X](mcas = mcas, strategy = stepper)
  }

  private[this] final def performInternal0[F[_], X >: B](
    mcas: Mcas,
    strategy: RetryStrategy,
  )(implicit F: Async[F]): F[X] = {
    F.uncancelable { poll =>
      F.defer {
        new Rxn.InterpreterState[X](
          this,
          mcas = mcas,
          strategy = strategy,
          isStm = false,
        ).interpretAsync(poll)
      }
    }
  }

  /** Only for tests/benchmarks */
  private[choam] final def unsafePerformInternal(
    ctx: Mcas.ThreadContext,
    str: RetryStrategy.Spin = RetryStrategy.Default,
  ): B = {
    new Rxn.InterpreterState[B](
      this,
      ctx.impl,
      strategy = str,
      isStm = false,
    ).interpretSyncWithContext(ctx)
  }

  private[choam] final def performStm[F[_], X >: B](
    mcas: Mcas,
    strategy: RetryStrategy,
  )(implicit F: Async[F]): F[X] = {
    // It is unsafe to accept a `Stepper` through
    // this method, since it could be a `Stepper[G]`,
    // where `G` is different form `F`:
    require(!strategy.isDebug)
    this.performStmInternal[F, X](mcas, strategy)
  }

  private[choam] final def performStmWithStepper[F[_], X >: B](
    mcas: Mcas,
    stepper: RetryStrategy.Internal.Stepper[F],
  )(implicit F: Async[F]): F[X] = {
    this.performStmInternal[F, X](mcas, stepper)
  }

  private[this] final def performStmInternal[F[_], X >: B](
    mcas: Mcas,
    strategy: RetryStrategy,
  )(implicit F: Async[F]): F[X] = {
    require(strategy.canSuspend)
    F.uncancelable { poll =>
      F.defer {
        new Rxn.InterpreterState[X](
          this,
          mcas = mcas,
          strategy = strategy,
          isStm = true,
        ).interpretAsync(poll)
      }
    }
  }

  override def toString: String
}

private[choam] sealed abstract class RxnImpl[+B]
  extends Rxn[B] with Txn.UnsealedTxn[B] {

  final override def + [Y >: B](that: Rxn[Y]): RxnImpl[Y] =
    new Rxn.Choice[Y](this, that)

  final override def * [C](that: Rxn[C]): RxnImpl[(B, C)] =
    new Rxn.AndAlso[B, C](this, that)

  final override def product[C](that: Rxn[C]): Rxn[(B, C)] =
    this * that

  final override def ? : Rxn[Option[B]] =
    this.attempt

  final override def attempt: Rxn[Option[B]] =
    this.map(Some(_)) + Rxn.none

  final override def maybe: Rxn[Boolean] =
    this.as(true) + Rxn.false_

  final override def map[C](f: B => C): RxnImpl[C] =
    new Rxn.Map_(this, f)

  final override def as[C](c: C): RxnImpl[C] =
    new Rxn.As(this, c)

  final override def void: RxnImpl[Unit] =
    this.as(())

  final override def map2[C, D](that: Rxn[C])(f: (B, C) => D): RxnImpl[D] =
    new Rxn.Map2(this, that, f)

  final override def <* [C](that: Rxn[C]): Rxn[B] =
    this.productL(that)

  final override def productL [C](that: Rxn[C]): RxnImpl[B] =
    (this * that).map(_._1)

  final override def *> [C](that: Rxn[C]): Rxn[C] =
    this.productR(that)

  final override def productR[C](that: Rxn[C]): RxnImpl[C] =
    new Rxn.ProductR[B, C](this, that)

  final override def flatMap[C](f: B => Rxn[C]): Rxn[C] =
    new Rxn.FlatMap(this, f)

  final override def >> [C](that: => Rxn[C]): Rxn[C] =
    this.flatMap { _ => that }

  final override def flatTap(f: B => Rxn[Unit]): Rxn[B] = {
    this.flatMap { b => f(b).as(b) }
  }

  final override def flatten[C](implicit ev: B <:< Rxn[C]): RxnImpl[C] =
    new Rxn.Flatten[C](this.asInstanceOf[Rxn[Rxn[C]]])

  final override def postCommit(pc: B => Rxn[Unit]): Rxn[B] =
    Rxn.postCommit(this, pc)

  // STM:

  final override def flatMap[C](f: B => Txn[C]): Txn[C] = {
    new Rxn.FlatMap(this, f.asInstanceOf[Function1[B, Rxn[C]]])
  }

  final override def flatten[C](implicit ev: B <:< Txn[C]): Txn[C] = {
    new Rxn.Flatten[C](this.asInstanceOf[Rxn[Rxn[C]]])
  }

  final override def map2[C, D](that: Txn[C])(f: (B, C) => D): Txn[D] = {
    this.map2[C, D](that.impl : Rxn[C])(f)
  }

  final override def productR[C](that: Txn[C]): Txn[C] = {
    this.productR[C](that.impl : Rxn[C])
  }

  final override def *> [C](that: Txn[C]): Txn[C] = {
    this.productR[C](that.impl : Rxn[C])
  }

  final override def productL[C](that: Txn[C]): Txn[B] = {
    this.productL[C](that.impl : Rxn[C])
  }

  final override def <* [C](that: Txn[C]): Txn[B] = {
    this.productL[C](that.impl : Rxn[C])
  }

  final override def product[C](that: Txn[C]): Txn[(B, C)] = {
    this * that.impl
  }

  final override def orElse[Y >: B](that: Txn[Y]): Txn[Y] = {
    new Rxn.OrElse(this, that.impl)
  }

  private[choam] final override def impl: RxnImpl[B] =
    this.asInstanceOf[RxnImpl[B]] // Note: this is unsafe in general, we must take care to only use it on Txns

  // /STM
}

/** This is specifically only for `Ref` to use! */
private[choam] abstract class RefGetAxn[B] extends RxnImpl[B] {
  private[choam] def cast[A]: MemoryLocation[A]
}

object Rxn extends RxnInstances0 {

  private[this] final val interruptCheckPeriod =
    16384

  // API:

  @inline
  final def pure[A](a: A): Rxn[A] =
    pureImpl(a)

  private[choam] final def pureImpl[A](a: A): RxnImpl[A] =
    new Rxn.Pure[A](a)

  /** Old name of `pure` */
  private[choam] final def ret[A](a: A): Rxn[A] =
    pure(a)

  private[this] val _unit: RxnImpl[Unit] =
    pureImpl(())

  private[this] val _none: Rxn[Option[Nothing]] =
    pure(None)

  private[this] val _true: RxnImpl[Boolean] =
    pureImpl(true)

  private[this] val _false: RxnImpl[Boolean] =
    pureImpl(false)

  @inline
  final def unit: Rxn[Unit] =
    unitImpl

  private[choam] final def unitImpl: RxnImpl[Unit] =
    _unit

  private[choam] final def none[A]: Rxn[Option[A]] =
    _none

  private[choam] final def true_[A]: Rxn[Boolean] =
    _true

  private[choam] final def false_[A]: Rxn[Boolean] =
    _false

  final def postCommit(pc: Rxn[Unit]): Rxn[Unit] =
    new Rxn.PostCommit[Unit](unit, _ => pc)

  private[core] final def postCommit[A](rxn: Rxn[A], pc: A => Rxn[Unit]): Rxn[A] =
    new Rxn.PostCommit(rxn, pc)

  @inline
  final def tailRecM[A, B](a: A)(f: A => Rxn[Either[A, B]]): Rxn[B] =
    tailRecMImpl(a)(f)

  private[choam] final def tailRecMImpl[X, A, B](a: A)(f: A => Rxn[Either[A, B]]): RxnImpl[B] =
    new Rxn.TailRecM[A, B](a, f)

  // Utilities:

  private[this] val _fastRandom: Random[Rxn] =
    random.newFastRandom

  private[this] val _secureRandom: SecureRandom[Rxn] =
    random.newSecureRandom

  private[this] val _unique: RxnImpl[Unique.Token] =
    Rxn.unsafe.delayImpl { new Unique.Token() }

  @inline
  final def unique: Rxn[Unique.Token] =
    uniqueImpl

  private[choam] final def uniqueImpl: RxnImpl[Unique.Token] =
    _unique

  @inline
  final def newUuid: Rxn[UUID] =
    newUuidImpl

  private[core] final val newUuidImpl: RxnImpl[UUID] =
    random.newUuidImpl

  final def fastRandom: Random[Rxn] =
    _fastRandom

  final def secureRandom: SecureRandom[Rxn] =
    _secureRandom

  final def deterministicRandom(
    initialSeed: Long,
    str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default
  ): Rxn[random.SplittableRandom[Rxn]] = {
    random.deterministicRandom(initialSeed, str)
  }

  final def memoize[A](rxn: Rxn[A], str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default): Rxn[Memo[A]] =
    Memo(rxn, str)

  private[choam] final object ref {

    private[choam] final def updSet1[A](r: Ref[A], nv: A): Rxn[Unit] =
      new Rxn.UpdSet1(r.loc, nv)

    private[choam] final def updUpdate1[A](r: Ref[A])(f: A => A): Rxn[Unit] =
      new Rxn.UpdUpdate1(r.loc, f)

    private[choam] final def modifyWith[A, B, C](r: Ref[A])(f: A => Rxn[(A, C)]): Rxn[C] =
      new Rxn.UpdWith[A, C](r.loc, f)
  }

  private[choam] final object loc {

    private[choam] final def modify[A, B](r: MemoryLocation[A])(f: A => (A, B)): RxnImpl[B] =
      new Rxn.UpdFull(r, f)
  }

  final object unsafe {

    import core.unsafe.RxnLocal

    trait WithLocal[A, I, R] {
      def apply[G[_]](
        local: RxnLocal[G, A],
        lift: Rxn ~> G,
        instances: RxnLocal.Instances[G],
      ): G[R]
    }

    trait WithLocalArray[A, I, R] {
      def apply[G[_]](
        arr: RxnLocal.Array[G, A],
        lift: Rxn ~> G,
        instances: RxnLocal.Instances[G],
      ): G[R]
    }

    @inline
    final def withLocal[A, I, R](initial: A, body: WithLocal[A, I, R]): Rxn[R] =
      RxnLocal.withLocal(initial, body)

    @inline
    final def withLocalArray[A, I, R](size: Int, initial: A, body: WithLocalArray[A, I, R]): Rxn[R] =
      RxnLocal.withLocalArray(size, initial, body)

    sealed abstract class Ticket[A] {
      def unsafePeek: A
      def unsafeSet(nv: A): Rxn[Unit]
      def unsafeIsReadOnly: Boolean
      def unsafeValidate: Rxn[Unit]
    }

    private[Rxn] final class TicketForTicketRead[A](hwd: LogEntry[A])
      extends Ticket[A] {

      final override def unsafePeek: A =
        hwd.nv

      final override def unsafeSet(nv: A): Rxn[Unit] =
        new Rxn.TicketWrite(hwd, nv)

      final override def unsafeIsReadOnly: Boolean =
        hwd.readOnly

      final override def unsafeValidate: Rxn[Unit] =
        this.unsafeSet(this.unsafePeek)
    }

    final def directRead[A](r: Ref[A]): Rxn[A] =
      new Rxn.DirectRead[A](r.loc)

    final def ticketRead[A](r: Ref[A]): Rxn[unsafe.Ticket[A]] =
      new Rxn.TicketRead[A](r.loc)

    /**
     * Reads from `r`, but without putting it into the log.
     *
     * Preserves opacity (i.e., automatically retries if needed),
     * so it is safer than `ticketRead`; but makes it impossible
     * to do a log extension later.
     */
    final def tentativeRead[A](r: Ref[A]): Rxn[A] =
      new Rxn.TentativeRead[A](r.loc)

    final def unread[A](r: Ref[A]): Rxn[Unit] =
      new Rxn.Unread(r)

    private[choam] final def cas[A](r: Ref[A], ov: A, nv: A): Rxn[Unit] = // TODO: do we even need this?
      new Rxn.Cas[A](r.loc, ov, nv)

    @inline
    private[choam] final def retry[A]: Rxn[A] =
      retryImpl[A]

    private[choam] final def retryImpl[A]: RxnImpl[A] =
      Rxn._AlwaysRetry.asInstanceOf[RxnImpl[A]]

    /**
     * This is primarily for STM to use, so be very careful!
     *
     * If this finds no `orElse` alternatives, it would try to suspend
     * until a ref in the log changes. That won't work for an `Rxn`.
     *
     * The only way to use this safely is to guarantee that an alt exists:
     * `(... *> retryWhenChanged) orElse (<someting which doesn't retry>)`.
     */
    @inline
    private[choam] final def retryWhenChanged[A]: Rxn[A] =
      StmImpl.retryWhenChanged[A]

    /**
     * This is primarily for STM to use, so be very careful!
     *
     * See the comment for `retryWhenChanged`.
     */
    private[choam] final def orElse[A, B](left: Rxn[B], right: Rxn[B]): Rxn[B] =
      new OrElse(left, right)

    @inline
    final def delay[B](uf: => B): Rxn[B] =
      delayImpl(uf)

    @inline
    private[choam] final def delayImpl[B](uf: => B): RxnImpl[B] =
      new Rxn.Lift[B](() => { uf })

    private[choam] final def suspend[B](uf: => Rxn[B]): Rxn[B] =
      suspendImpl(uf)

    private[choam] final def suspendImpl[A](uf: => Rxn[A]): RxnImpl[A] =
      delayImpl(uf).flatten // TODO: optimize

    private[choam] final def delayContext[B](uf: Mcas.ThreadContext => B): Rxn[B] =
      new Rxn.Ctx1[B](uf)

    private[choam] final def suspendContext[B](uf: Mcas.ThreadContext => Rxn[B]): Rxn[B] =
      delayContext(uf).flatten

    /**
     * Calling `unsafePerform` (or similar) inside
     * `uf` is dangerous, so handle with care!
     */
    private[choam] final def delayContextImpl[A](uf: Mcas.ThreadContext => A): RxnImpl[A] =
      new Rxn.Ctx1[A](uf)

    @inline
    final def panic[A](ex: Throwable): Rxn[A] =
      panicImpl(ex)

    private[choam] final def panicImpl[A](ex: Throwable): RxnImpl[A] =
      delayImpl[A] { throw ex }

    private[choam] final def assert(cond: Boolean): Rxn[Unit] =
      if (cond) unit else panic[Unit](new AssertionError)

    private[choam] final def exchanger[A, B]: Rxn[Exchanger[A, B]] =
      Exchanger.apply[A, B]

    /**
     * This is not unsafe by itself, but it is only useful
     * if there are other unsafe things going on (validation
     * is handled automatically otherwise). This is why it
     * is part of the `unsafe` API.
     */
    final def forceValidate: Rxn[Unit] =
      new Rxn.ForceValidate

    // Unsafe/imperative API:

    /** Embeds a block of code, which uses the unsafe/imperative API, into a `Rxn`. */
    final def embedUnsafe[A](unsafeBlock: dev.tauri.choam.unsafe.InRxn2 => A): Rxn[A] = {
      new Rxn.Ctx3[Rxn[A]]({ (state: unsafe2.InRxn2) =>
        try {
          pure[A](unsafeBlock(state))
        } catch {
          case _: unsafe2.RetryException =>
            retry[A]
        }
      }).flatten
    }

    /** Internal API called by `atomically` */
    private[choam] final def startImperative(mcasImpl: Mcas, str: RetryStrategy): unsafe2.InRxn = {
      new Rxn.InterpreterState[Any](
        rxn = null,
        mcas = mcasImpl,
        strategy = str,
        isStm = false,
      )
    }
  }

  private[choam] final object internal {

    final def exchange[A, B](ex: ExchangerImpl[A, B], a: A): Rxn[B] =
      new Rxn.Exchange[A, B](ex, a)

    final def finishExchange[D](
      hole: Ref[Exchanger.NodeResult[D]],
      restOtherContK: ListObjStack.Lst[Any],
      lenSelfContT: Int,
      selfDesc: Descriptor,
      mergeDescs: Rxn[Any],
    ): Rxn[Unit] = {
      new Rxn.FinishExchange(hole, restOtherContK, lenSelfContT, selfDesc, mergeDescs.asInstanceOf[MergeDescs])
    }

    final def mergeDescs(): Rxn[Any] =
      new Rxn.MergeDescs

    final def newLocal(local: InternalLocal): RxnImpl[Unit] =
      new Rxn.LocalNewEnd(local, isEnd = false)

    final def endLocal(local: InternalLocal): RxnImpl[Unit] =
      new Rxn.LocalNewEnd(local, isEnd = true)
  }

  private[choam] final object StmImpl {

    private[choam] final def retryWhenChanged[A]: RxnImpl[A] =
      _RetryWhenChanged.asInstanceOf[RxnImpl[A]]
  }

  // Representation:

  /** Only the interpreter can use this! */
  private final class Commit[A]() extends RxnImpl[A] {
    final override def toString: String = "Commit()"
  }

  private final class AlwaysRetry[B]() extends RxnImpl[B] {
    final override def toString: String = "AlwaysRetry()"
  }

  private[core] val _AlwaysRetry: RxnImpl[Any] =
    new AlwaysRetry

  private final class PostCommit[A](val rxn: Rxn[A], val pc: A => Rxn[Unit]) extends RxnImpl[A] {
    final override def toString: String = s"PostCommit(${rxn}, <function>)"
  }

  private final class Lift[B](val func: Function0[B]) extends RxnImpl[B] {
    final override def toString: String = "Lift(<function>)"
  }

  private[this] final class RetryWhenChanged[A]() extends RxnImpl[A] { // STM
    final override def toString: String = "RetryWhenChanged()"
  }

  private[this] val _RetryWhenChanged: RxnImpl[Any] =
    new RetryWhenChanged[Any]

  private[core] final class Choice[B](val left: Rxn[B], val right: Rxn[B]) extends RxnImpl[B] {
    final override def toString: String = s"Choice(${left}, ${right})"
  }

  private final class Cas[A](val ref: MemoryLocation[A], val ov: A, val nv: A) extends RxnImpl[Unit] {
    final override def toString: String = s"Cas(${ref}, ${ov}, ${nv})"
  }

  private[core] final class Map2[B, C, D](val left: Rxn[B], val right: Rxn[C], val f: (B, C) => D) extends RxnImpl[D] {
    final override def toString: String = s"Map2(${left}, ${right}, <function>)"
  }

  private sealed abstract class UpdBase[B, X](val ref: MemoryLocation[X]) extends RxnImpl[B] {
    final override def toString: String = s"Upd(${ref}, <function>)"
  }

  private sealed abstract class UpdSingle[X](ref0: MemoryLocation[X]) extends UpdBase[Unit, X](ref0) {
    def f(ov: X): X
  }

  private sealed abstract class UpdTuple[B, X](ref0: MemoryLocation[X]) extends UpdBase[B, X](ref0) {
    def f(ov: X): (X, B)
  }

  private final class UpdFull[B, X](ref0: MemoryLocation[X], f0: X => (X, B))
    extends UpdTuple[B, X](ref0) {
    final override def f(ov: X): (X, B) = f0(ov)
  }

  private final class UpdSet1[X](ref0: MemoryLocation[X], nv: X)
    extends UpdSingle[X](ref0) {
    final override def f(ov: X): X = nv
  }

  private final class UpdUpdate1[X](ref0: MemoryLocation[X], f0: X => X)
    extends UpdSingle[X](ref0) {
    final override def f(ov: X): X = f0(ov)
  }

  private final class TicketWrite[A](val hwd: LogEntry[A], val newest: A) extends RxnImpl[Unit] {
    final override def toString: String = s"TicketWrite(${hwd}, ${newest})"
  }

  private final class DirectRead[A](val ref: MemoryLocation[A]) extends RxnImpl[A] {
    final override def toString: String = s"DirectRead(${ref})"
  }

  private final class Exchange[A, B](val exchanger: ExchangerImpl[A, B], val a: A) extends RxnImpl[B] {
    final override def toString: String = s"Exchange(${exchanger}, ${a})"
  }

  private[core] final class AndAlso[B, D](val left: Rxn[B], val right: Rxn[D]) extends RxnImpl[(B, D)] {
    final override def toString: String = s"AndAlso(${left}, ${right})"
  }

  /** Only the interpreter can use this! */
  private final class Done[A](val result: A) extends RxnImpl[A] {
    final override def toString: String = s"Done(${result})"
  }

  private sealed abstract class Ctx[B] extends RxnImpl[B] {
    final override def toString: String = s"Ctx(<block>)"
    def uf(ctx: Mcas.ThreadContext, ir: unsafe2.InRxn2): B
  }

  private final class Ctx1[B](_uf: Mcas.ThreadContext => B) extends Ctx[B] {
    final override def uf(ctx: Mcas.ThreadContext, ir: unsafe2.InRxn2): B = _uf(ctx)
  }

  private final class Ctx3[B](_uf: unsafe2.InRxn2 => B) extends Ctx[B] {
    final override def uf(ctx: Mcas.ThreadContext, ir: unsafe2.InRxn2): B = _uf(ir)
  }

  private final class UpdWith[A, C](val ref: MemoryLocation[A], val f: A => Rxn[(A, C)]) extends RxnImpl[C] {
    final override def toString: String = s"UpdWith(${ref}, <function>)"
  }

  private[core] final class As[A, B, C](val rxn: Rxn[B], val c: C) extends RxnImpl[C] {
    final override def toString: String = s"As(${rxn}, ${c})"
  }

  /** Only the interpreter/exchanger can use this! */
  private final class FinishExchange[D](
    val hole: Ref[Exchanger.NodeResult[D]],
    val restOtherContK: ListObjStack.Lst[Any],
    val lenSelfContT: Int,
    val selfDesc: Descriptor,
    val mergeDescs: MergeDescs,
  ) extends RxnImpl[Unit] {

    final override def toString: String = {
      val rockLen = ListObjStack.Lst.length(this.restOtherContK)
      s"FinishExchange(${hole}, <ListObjStack.Lst of length ${rockLen}>, ${lenSelfContT})"
    }
  }

  private final class MergeDescs extends RxnImpl[Any] {

    /** TODO: this is a mess... */
    var otherDesc: Descriptor =
      null

    final override def toString: String = {
      "MergeDescs()"
    }
  }

  private final class TicketRead[A](val ref: MemoryLocation[A]) extends RxnImpl[Rxn.unsafe.Ticket[A]] {
    final override def toString: String = s"TicketRead(${ref})"
  }

  private final class ForceValidate() extends RxnImpl[Unit] {
    final override def toString: String = s"ForceValidate()"
  }

  private final class Pure[A](val a: A) extends RxnImpl[A] {
    final override def toString: String = s"Pure(${a})"
  }

  private[core] final class ProductR[B, C](val left: Rxn[B], val right: Rxn[C]) extends RxnImpl[C] {
    final override def toString: String = s"ProductR(${left}, ${right})"
  }

  private[core] final class FlatMap[A, B, C](val rxn: Rxn[B], val f: B => Rxn[C]) extends RxnImpl[C] {
    final override def toString: String = s"FlatMap(${rxn}, <function>)"
  }

  private[core] final class Flatten[B](val rxn: Rxn[Rxn[B]]) extends RxnImpl[B] {
    final override def toString: String = s"Flatten(${rxn})"
  }

  /** Only the interpreter can use this! */
  private sealed abstract class SuspendUntil
    extends RxnImpl[Nothing]
    with unsafe2.CanSuspendInF {

    def toF[F[_]](
      mcasImpl: Mcas,
      mcasCtx: Mcas.ThreadContext,
    )(implicit F: Async[F]): F[Rxn[Any]]

    final override def suspend[F[_]](
      mcasImpl: Mcas,
      mcasCtx: Mcas.ThreadContext,
    )(implicit F: Async[F]): F[Unit] = {
      F.flatMap(this.toF[F](mcasImpl, mcasCtx)) {
        case null =>
          F.unit
        case x =>
          F.delay(impossible(s"toF returned $x"))
      }
    }
  }

  private final class SuspendUntilBackoff(val token: Long) extends SuspendUntil {

    _assert(!Backoff2.isPauseToken(token))

    final override def toString: String =
      s"SuspendUntilBackoff(${token.toHexString})"

    final override def toF[F[_]](
      mcasImpl: Mcas,
      mcasCtx: Mcas.ThreadContext,
    )(implicit F: Async[F]): F[Rxn[Any]] =
      F.as(Backoff2.tokenToF[F](token), null)
  }

  private final class SuspendWithStepper[F[_]](
    stepper: RetryStrategy.Internal.Stepper[F],
    nextRxn: F[Rxn[Any]],
  ) extends SuspendUntil {

    final override def toString: String =
      s"SuspendWithStepper(...)"

    final override def toF[G[_]](
      mcasImpl: Mcas,
      mcasCtx: Mcas.ThreadContext,
    )(implicit G: Async[G]): G[Rxn[Any]] = {
      // Note: these casts are "safe", since `perform[Stm]WithStepper`
      // sets things up so that `F` and `G` are the same.
      G.productR(G.flatten(stepper.newSuspension.asInstanceOf[G[G[Unit]]]))(nextRxn.asInstanceOf[G[Rxn[Any]]])
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
    )(implicit F: Async[F]): F[Rxn[Any]] = {
      if ((desc ne null) && (desc.size > 0)) {
        F.cont(new Cont[F, Rxn[Any], Rxn[Any]] {
          final override def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
            G.uncancelable[Rxn[Any]] { poll =>
              G.flatten {
                lift(F.delay[G[Rxn[Any]]] {
                  val rightNull: Either[Throwable, Rxn[Any]] = Right(null)
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

  private final class TailRecM[A, B](val a: A, val f: A => Rxn[Either[A, B]]) extends RxnImpl[B] {
    final override def toString: String = s"TailRecM(${a}, <function>)"
  }

  private[core] final class Map_[B, C](val rxn: Rxn[B], val f: B => C) extends RxnImpl[C] {
    final override def toString: String = s"Map_(${rxn}, <function>)"
  }

  private[core] final class OrElse[B](val left: Rxn[B], val right: Rxn[B]) extends RxnImpl[B] { // STM
    final override def toString: String = s"OrElse(${left}, ${right})"
  }

  private final class Unread[A](val ref: Ref[A]) extends RxnImpl[Unit] {
    final override def toString: String = s"Unread(${ref})"
  }

  private final class LocalNewEnd(val local: InternalLocal, val isEnd: Boolean) extends RxnImpl[Unit] {
    final override def toString: String = s"LocalNewEnd(${local}, ${isEnd})"
  }

  private final class TentativeRead[A](val loc: MemoryLocation[A]) extends RxnImpl[A] {
    final override def toString: String = s"TentativeRead(${loc})"
  }

  // Syntax helpers:

  final class InvariantSyntax[A](private val self: Rxn[A]) extends AnyVal {
    final def run[F[_]](implicit F: Reactive[F]): F[A] =
      F.run(self)
  }

  // Interpreter:

  private[this] final class PostCommitResultMarker // TODO: make this a java enum?
  private[this] final val postCommitResultMarker =
    new PostCommitResultMarker

  private[core] final val commitSingleton: Rxn[Any] = // TODO: make this a java enum?
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

  final class MaxRetriesExceeded(val maxRetries: Int)
    extends Exception(s"exceeded maxRetries of ${maxRetries}") {

    final override def fillInStackTrace(): Throwable =
      this

    final override def initCause(cause: Throwable): Throwable =
      throw new IllegalStateException
  }

  // TODO: Consider using JVM exception chaining
  // TODO: instead of this "composite" exception.
  // TODO: But: where to store `committedResult`?
  final class PostCommitException private[Rxn] (
    val committedResult: Any,
    val errors: NonEmptyList[Throwable],
  ) extends Exception(s"${errors.size} exception(s) encountered during post-commit action(s)") {

    final override def fillInStackTrace(): Throwable =
      this

    final override def initCause(cause: Throwable): Throwable =
      throw new IllegalStateException
  }

  /** Panic while doing execution for the "other" side of an exchange */
  private[this] final class ExchangePanic(val ex: Throwable)

  private[this] final object ExchangePanicMarker

  private final class InterpreterState[R](
    rxn: Rxn[R],
    mcas: Mcas,
    strategy: RetryStrategy,
    isStm: Boolean,
  ) extends Hamt.EntryVisitor[MemoryLocation[Any], LogEntry[Any], Rxn[Any]]
    with unsafe2.InRxn.UnsealedInRxn {

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

    private[choam] final override def invalidateCtx(): Unit = {
      this.ctx = null
      this._stats = null
      this._exParams = null
    }

    private[this] var startRxn: Rxn[Any] = rxn

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
    private[this] val stmAlts: ArrayObjStack[Any] = new ArrayObjStack[Any](initSize = 2)

    private[this] var locals: IdentityHashMap[InternalLocal, AnyRef] =
      null

    private[this] val contT: ByteStack = new ByteStack(initSize = 8)
    private[this] var contK: ObjStack[Any] = mkInitialContK()
    private[this] val pc: ListObjStack[Rxn[Unit]] = new ListObjStack[Rxn[Unit]]()
    private[this] var pcErrors: List[Throwable] = Nil
    private[this] val commit = commitSingleton
    contT.push2(RxnConsts.ContAfterPostCommit, RxnConsts.ContAndThen)

    private[this] var contTReset: Array[Byte] = contT.takeSnapshot()
    private[this] var contKReset: ListObjStack.Lst[Any] = objStackWithOneCommit

    private[this] var a: Any = null

    @inline
    private[this] final def aCastTo[A]: A = {
      this.a.asInstanceOf[A]
    }

    private[this] var retries: Int =
      0

    /** How many times was `desc` revalidated and successfully extended? */
    private[this] var descExtensions: Int =
      0

    /** Initially `true`, and if an MCAS cycle is detected, becomes `false` (and then remains `false`) */
    private[this] var optimisticMcas: Boolean =
      true

    /** Initially `true`, and if a `+` is encountered, becomes `false` (and then remains `false`) */
    private[this] var mutable: Boolean = // TODO: this makes it slower if there is `+`! (See `InterpreterBench`.)
      true

    /**
     * Becomes `true` when the first `tentativeRead` is executed.
     * If `true`, instead of a possible log extension we just
     * retry (because in the presence of a `tentativeRead` we can't
     * revalidate the log).
     */
    private[this] var hasTentativeRead: Boolean =
      false

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

    final override def entryAbsent(ref: MemoryLocation[Any], curr: Rxn[Any]): LogEntry[Any] = {
      val res: LogEntry[Any] = curr match {
        case _: RefGetAxn[_] =>
          this.ctx.readIntoHwd(ref)
        case c: UpdBase[_, _] =>
          val hwd = this.ctx.readIntoHwd(c.ref)
          if (this.desc.isValidHwd(hwd)) {
            val ox = hwd.nv
            val nx = c match {
              case c: UpdSingle[_] =>
                val nx = c.f(ox)
                this.a = ()
                nx
              case c: UpdTuple[_, _] =>
                val (nx, b) = c.f(ox)
                this.a = b
                nx
            }
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

    final override def entryPresent(ref: MemoryLocation[Any], hwd: LogEntry[Any], curr: Rxn[Any]): LogEntry[Any] = {
      _assert(hwd ne null)
      val res: LogEntry[Any] = curr match {
        case _: RefGetAxn[_] =>
          hwd
        case c: UpdBase[_, x] =>
          val ox = hwd.cast[x].nv
          val nx = c match {
            case c: UpdSingle[_] =>
              val nx = c.f(ox)
              this.a = ()
              nx
            case c: UpdTuple[_, _] =>
              val (nx, b) = c.f(ox)
              this.a = b
              nx
          }
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

    private[this] final def saveAlt[A, B](k: Rxn[B]): Unit = {
      _saveAlt(this.alts, k)
    }

    private[this] final def saveStmAlt[A, B](k: Rxn[B]): Unit = {
      _saveAlt(this.stmAlts, k)
    }

    private[this] final def _saveAlt[A, B](alts: ArrayObjStack[Any], k: Rxn[B]): Unit = {
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

    private[this] final def tryLoadAlt(isPermanentFailure: Boolean): Rxn[R] = {
      if (isPermanentFailure) {
        _tryLoadAlt(this.stmAlts, isPermanentFailure)
      } else {
        _tryLoadAlt(this.alts, isPermanentFailure)
      }
    }

    private[this] final def discardStmAlt(): Unit = {
      this.stmAlts.popAndDiscard(7)
    }

    private[this] final def _tryLoadAlt(alts: ArrayObjStack[Any], isPermanentFailure: Boolean): Rxn[R] = {
      if (alts.nonEmpty()) {
        val res = alts.pop().asInstanceOf[Rxn[R]]
        this._loadRestOfAlt(alts, isPermanentFailure = isPermanentFailure)
        res
      } else {
        null
      }
    }

    private[this] final def mergeDescForOrElse(newDesc: Descriptor, isPermanentFailure: Boolean): Descriptor = {
      if (isPermanentFailure) {
        val discarded = _desc
        if ((discarded ne null) && discarded.nonEmpty) {
          Descriptor.mergeReadsInto(newDesc, discarded)
        } else {
          newDesc
        }
      } else {
        // it is not an `orElse`, but a `+`
        newDesc
      }
    }

    private[this] final def _loadRestOfAlt(alts: ArrayObjStack[Any], isPermanentFailure: Boolean): Unit = {
      pc.loadSnapshot(alts.pop().asInstanceOf[ListObjStack.Lst[Rxn[Unit]]])
      contKList.loadSnapshot(alts.pop().asInstanceOf[ListObjStack.Lst[Any]])
      contT.loadSnapshot(alts.pop().asInstanceOf[Array[Byte]])
      a = alts.pop()
      _desc = this.mergeDescForOrElse(alts.pop().asInstanceOf[Descriptor], isPermanentFailure = isPermanentFailure)
      loadLocalsSnapshot(alts.pop().asInstanceOf[AnyRef])
    }

    private[this] final def loadAltFrom(msg: Exchanger.Msg): Either[Throwable, Any] = {
      pc.loadSnapshot(msg.postCommit)
      contKList.loadSnapshot(msg.contK)
      contT.loadSnapshot(msg.contT)
      // TODO: write a test for this (exchange + STM)
      desc = this.mergeDescForOrElse(msg.desc, isPermanentFailure = false) // TODO: is `false` correct here?
      _assert(msg.state match {
        case Exchanger.Msg.Initial =>
          false // mustn't happen
        case Exchanger.Msg.Claimed => // we've claimed the offer, and need to finish the exchange
          true // we can continue with anything
        case Exchanger.Msg.Finished => // the other thread finished the exchange, we're done
          (contT.peek() == RxnConsts.ContAndThen) && equ(contK.peek(), commitSingleton) && msg.desc.isEmpty
      })
      msg.value match {
        case l @ Left(_) =>
          _assert(msg.state eq Exchanger.Msg.Finished)
          l
        case r @ Right(_) =>
          r
      }
    }

    private[this] final def popFinalResult(): Any = {
      val r = contK.pop()
      _assert(!equ(r, postCommitResultMarker))
      r
    }

    @tailrec
    private[this] final def nextOnPanic(ex: Throwable): Rxn[Any] = {
      // TODO: We don't actually have proper panic handlers.
      // TODO: For now, we're special casing 2 situations
      // TODO: where panic handling is needed: (1) post-commit
      // TODO: actions (where subsequent PC actions need to be
      // TODO: executed even if one of them panics); and
      // TODO: (2) when executing the "other" side of an
      // TODO: exchange (where we need to make the other side
      // TODO: panic, but ourselves have to retry).
      val contK = this.contK
      (contT.pop() : @switch) match {
        case 0 => // ContAndThen
          contK.peek() match {
            case _: FinishExchange[_] =>
              // Special case: panic in the Rxn we're executing
              // on behalf of the "other" side of the exchange;
              // we'll pass back the exception to the other side
              // (see FinishExchange handling).
              a = new ExchangePanic(ex)
              contT.push(RxnConsts.ContAndThen)
              next()
            case _ =>
              contK.pop()
              nextOnPanic(ex)
          }
        case 1 => // ContAndAlso
          contK.pop() // next() does pop-pop-push
          nextOnPanic(ex)
        case 2 => // ContAndAlsoJoin
          contK.pop()
          nextOnPanic(ex)
        case 3 => // ContTailRecM
          contK.pop()
          contK.pop()
          nextOnPanic(ex)
        case 4 => // ContPostCommit
          impossible("nextOnPanic reached ContPostCommit")
        case 5 => // ContAfterPostCommit
          // no handler found, just throw it:
          _assert(this.pcErrors.isEmpty)
          throw ex
        case 6 => // ContCommitPostCommit
          // post-commit action panic'd, so we won't
          // commit it, just save the error for later:
          this.pcErrors = ex :: this.pcErrors
          next() // continue with next PC or final result
        case 7 => // ContUpdWith
          contK.pop()
          contK.pop()
          nextOnPanic(ex)
        case 8 => // ContAs
          contK.pop()
          nextOnPanic(ex)
        case 9 => // ContProductR
          contK.pop()
          contK.pop()
          nextOnPanic(ex)
        case 10 => // ContFlatMapF
          impossible("ContFlatMapF")
        case 11 => // ContFlatMap
          contK.pop()
          contK.pop()
          nextOnPanic(ex)
        case 12 => // ContMap
          contK.pop()
          nextOnPanic(ex)
        case 13 => // ContMap2Right
          contK.pop() // next() does pop-pop-push
          nextOnPanic(ex)
        case 14 => // ContMap2Func
          contK.pop()
          contK.pop()
          nextOnPanic(ex)
        case 15 => // ContOrElse
          discardStmAlt()
          nextOnPanic(ex)
        case 16 => // ContFlatten
          nextOnPanic(ex)
        case 17 => // ContRegisterPostCommit
          contK.pop()
          nextOnPanic(ex)
        case ct => // mustn't happen
          impossible(s"Unknown contT: ${ct} (nextOnPanic)")
      }
    }

    @tailrec
    private[this] final def next(): Rxn[Any] = {
      val contK = this.contK
      (contT.pop() : @switch) match {
        case 0 => // ContAndThen
          contK.pop().asInstanceOf[Rxn[Any]]
        case 1 => // ContAndAlso
          val savedA = a
          a = contK.pop()
          val res = contK.pop().asInstanceOf[Rxn[Any]]
          contK.push(savedA)
          res
        case 2 => // ContAndAlsoJoin
          val savedA = contK.pop()
          a = (savedA, a)
          next()
        case 3 => // ContTailRecM
          val e = this.aCastTo[Either[Any, Any]]
          a = contK.peek()
          val f = contK.peekSecond().asInstanceOf[Any => Rxn[Any]]
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
          val pcAction = contK.pop().asInstanceOf[Rxn[Any]]
          clearAlts()
          setContReset()
          a = null
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
          commit.asInstanceOf[Rxn[Any]]
        case 7 => // ContUpdWith
          val ox = contK.pop()
          val ref = contK.pop().asInstanceOf[MemoryLocation[Any]]
          val (nx, res) = this.aCastTo[Tuple2[?, ?]]
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
          contK.pop().asInstanceOf[Rxn[Any]]
        case 10 => // ContFlatMapF
          impossible("ContFlatMapF")
        case 11 => // ContFlatMap
          val n = contK.pop().asInstanceOf[Function1[Any, Rxn[Any]]].apply(a)
          a = contK.pop()
          n
        case 12 => // ContMap
          val b = contK.pop().asInstanceOf[Function1[Any, Any]].apply(a)
          a = b
          next()
        case 13 => // ContMap2Right
          val savedA = a
          a = contK.pop()
          val n = contK.pop().asInstanceOf[Rxn[Any]]
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
        case 16 => // ContFlatten
          val nxt = a.asInstanceOf[Rxn[Any]]
          a = null
          nxt
        case 17 => // ContRegisterPostCommit
          val f = contK.pop().asInstanceOf[Any => Rxn[Unit]]
          pc.push(f(a))
          next()
        case ct => // mustn't happen
          impossible(s"Unknown contT: ${ct} (next)")
      }
    }

    private[this] final def retry(
      canSuspend: Boolean = this.canSuspend,
      permanent: Boolean = false,
      noDebug: Boolean = false,
    ): Rxn[Any] = {
      if (this.strategy.isDebug && (!noDebug)) {
        this.strategy match {
          case str @ ((_: RetryStrategy.Spin) | (_: RetryStrategy.StrategyFull)) =>
            impossible(s"$str returned isDebug == true")
          case stepper: RetryStrategy.Internal.Stepper[_] =>
            return new SuspendWithStepper(stepper, stepper.asyncF.delay { // scalafix:ok
              this.retry(canSuspend, permanent, noDebug = true)
            })
        }
      }
      val alt = tryLoadAlt(isPermanentFailure = permanent)
      if (alt ne null) {
        // we're not actually retrying,
        // just going to the other side
        // of a `+` or `orElse`, so we're
        // not incrementing `retries`:
        alt
      } else {
        _assert((!permanent) || this.isStm) // otherwise it is a misused `Rxn.unsafe.retryWhenChanged`
        // really retrying:
        val retriesNow = this.retries + 1
        this.retries = retriesNow
        // check abnormal conditions:
        val mr = this.maxRetries
        if ((mr >= 0) && ((retriesNow > mr) || (retriesNow == Integer.MAX_VALUE))) {
          // TODO: maybe we could represent "infinity" with MAX_VALUE instead of -1?
          throw new MaxRetriesExceeded(mr)
        } else {
          maybeCheckInterrupt(retriesNow)
        }
        // STM might still need these:
        val d = if (this.isStm) this._desc else null
        // restart everything:
        clearDesc()
        hasTentativeRead = false
        a = null
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
    ): Rxn[Any] = {
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
     * Returns `null` if we must retry.
     */
    private[this] final def tentativeRead[A](ref: MemoryLocation[A]): LogEntry[A] = {
      val hwd = readMaybeFromLog(ref)
      if (hwd ne null) {
        // `desc` must be initialized (at the latest) when we
        // execute the first `tentativeRead`, because for
        // those, opacity is solely based on version numbers
        // (so we need an initialized `validTs`):
        _assert(_desc ne null)
        this.hasTentativeRead = true
      }
      hwd
    }

    /** Returns `true` if successful, `false` if retry is needed */
    private[this] final def ticketWrite[A](c: TicketWrite[A]): Boolean = {
      _assert(this._entryHolder eq null) // just to be sure
      a = () : Any
      desc = desc.computeOrModify(c.hwd.cast[Any].address, tok = c, visitor = this)
      val newHwd = this._entryHolder
      this._entryHolder = null // cleanup
      val newHwd2 = revalidateIfNeeded(newHwd)
      (newHwd2 ne null)
    }

    /** Returns `true` if successful, `false` if retry is needed */
    private[this] final def handleUpd[B, C](c: UpdBase[B, C]): Boolean = {
      _assert(this._entryHolder eq null) // just to be sure
      desc = desc.computeOrModify(c.ref.cast[Any], tok = c.asInstanceOf[Rxn[Any]], visitor = this)
      val hwd = this._entryHolder
      this._entryHolder = null // cleanup
      if (!desc.isValidHwd(hwd)) {
        if (forceValidate(hwd)) {
          // OK, `desc` was extended;
          // but need to finish `Upd`:
          val ox = hwd.cast[C].nv
          val nx = c match {
            case c: UpdSingle[_] =>
              val nx = c.f(ox)
              this.a = ()
              nx
            case c: UpdTuple[_, _] =>
              val (nx, b) = c.f(ox)
              this.a = b
              nx
          }
          desc = desc.overwrite(hwd.withNv(nx).cast[Any])
          true
        } else {
          _assert((this._desc eq null) || this.hasTentativeRead)
          false
        }
      } else {
        true
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

    private[this] final def forceValidate(optHwd: LogEntry[?]): Boolean = {
      if (this.hasTentativeRead) {
        // can't revalidate and extend the log (safely),
        // because a `tentativeRead` was previously
        // executed (and those aren't in the log):
        false // need to roll back
      } else {
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
        // `Successful` is success; otherwise the result is:
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

    private[this] final def handleCommit(): Boolean = {
      val d = this._desc // we avoid calling `desc` here, in case it's `null`
      this.clearDesc()
      val dSize = if (d ne null) d.size else 0
      if (performMcas(d)) {
        if (Consts.statsEnabled) {
          // save retry statistics:
          ctx.recordCommit(retries = this.retries, committedRefs = dSize, descExtensions = this.descExtensions)
        }
        // Note: commit is done, but we still may need to perform post-commit actions
        true
      } else {
        false // need to retry
      }
    }

    private[this] final def preparePcActions(): Unit = {
      val res = a
      a = null
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
    }

    @tailrec
    private[this] final def loop[A, B](curr: Rxn[B]): R = {
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
      curr match {
        case _: Commit[_] => // Commit
          if (handleCommit()) {
            // ok, commit is done, but we still need to perform post-commit actions
            preparePcActions()
            loop(next())
          } else {
            contK.push(commit)
            contT.push(RxnConsts.ContAndThen)
            loop(retry())
          }
        case _: AlwaysRetry[_] => // AlwaysRetry
          loop(retry())
        case c: PostCommit[a] => // PostCommit
          contT.push(RxnConsts.ContRegisterPostCommit)
          contK.push(c.pc)
          loop(c.rxn)
        case c: Lift[_] => // Lift
          // TODO: Do we need to catch exceptions elsewhere? (This covers `delay`.)
          val f = c.func
          val nxt = try {
            a = f()
            null
          } catch {
            case ex if NonFatal(ex) =>
              nextOnPanic(ex)
          }
          loop(if (nxt ne null) nxt else next())
        case _: RetryWhenChanged[_] => // RetryWhenChanged (STM)
          loop(retry(canSuspend = this.canSuspend, permanent = true))
        case c: Choice[_] => // Choice
          saveAlt(c.right)
          loop(c.left)
        case c: Cas[_] => // Cas
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
        case refGet: RefGetAxn[_] => // RefGetAxn
          _assert(this._entryHolder eq null) // just to be sure
          desc = desc.computeIfAbsent(refGet.cast[Any], tok = refGet, visitor = this)
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
        case c: Map2[_, _, _] => // Map2
          contT.push2(RxnConsts.ContMap2Func, RxnConsts.ContMap2Right)
          contK.push3(c.f, c.right, a)
          loop(c.left)
        case c: UpdBase[_, _] =>
          val nxt = if (handleUpd(c)) {
            next()
          } else {
            retry()
          }
          loop(nxt)
        case c: TicketWrite[_] => // TicketWrite
          if (ticketWrite(c)) {
            loop(next())
          } else {
            _assert(this._desc eq null)
            loop(retry())
          }
        case c: DirectRead[_] => // DirectRead
          a = ctx.readDirect(c.ref)
          loop(next())
        case c: Exchange[_, _] => // Exchange
          val msg = Exchanger.Msg.newMsg(
            value = c.a,
            contK = contKList.takeSnapshot(),
            contT = contT.takeSnapshot(),
            desc = this.descImm, // TODO: could we just call `toImmutable`?
            postCommit = pc.takeSnapshot(),
            exchangerData = stats,
            hasTentativeRead = this.hasTentativeRead,
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
              this.hasTentativeRead = contMsg.hasTentativeRead
              val nxt = loadAltFrom(contMsg) match {
                case Left(ex) =>
                  // the other side encountered a panic while
                  // executing our Rxn, so we must handle it:
                  a = ExchangePanicMarker // just to help with debugging if anyone uses this
                  nextOnPanic(ex)
                case Right(result) =>
                  a = result
                  next()
              }
              loop(nxt)
          }
        case c: AndAlso[_, _] => // AndAlso
          contT.push2(RxnConsts.ContAndAlsoJoin, RxnConsts.ContAndAlso)
          contK.push2(c.right, a)
          // left:
          loop(c.left)
        case c: Done[_] => // Done
          val committedResult: R = c.result.asInstanceOf[R]
          this.pcErrors.reverse match {
            case h :: t =>
              throw new PostCommitException(committedResult, NonEmptyList(h, t))
            case Nil =>
              committedResult
          }
        case c: Ctx[_] =>
          val b = c.uf(ctx, this)
          a = b
          loop(next())
        case c: UpdWith[_, _] =>
          val hwd = readMaybeFromLog(c.ref)
          if (hwd eq null) {
            loop(retry())
          } else {
            val ox = hwd.nv
            val rxn = c.f(ox)
            desc = desc.addOrOverwrite(hwd)
            contT.push(RxnConsts.ContUpdWith)
            contK.push2(c.ref, ox)
            // TODO: if `rxn` writes to the same ref, we'll throw (see above)
            loop(rxn)
          }
        case c: As[_, _, _] => // As
          contT.push(RxnConsts.ContAs)
          contK.push(c.c)
          loop(c.rxn)
        case c: FinishExchange[d] =>
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
          val resultToOther = a match {
            case ep: ExchangePanic =>
              // while executing the "other" Rxn, a panic happened;
              // this error is not ours, so we pass it back to the
              // other side:
              Left(ep.ex)
            case _ =>
              Right(aCastTo[d])
          }
          val fx = new Exchanger.FinishedEx[d](
            result = resultToOther,
            contK = c.restOtherContK,
            contT = otherContT,
            hasTentativeRead = this.hasTentativeRead,
          )
          a = contK.pop() // the exchanged value we've got from the other thread
          val nxt = resultToOther match {
            case Left(_) =>
              // While executing the "other" Rxn, a panic happened;
              // normally we'd only fill c.hole when we commit, but
              // this is a special case: we want the other side to
              // immediately panic.
              val singleCasDesc = ctx.addCasFromInitial(ctx.startSnap(), c.hole.loc, null, fx).toImmutable
              ctx.tryPerform(singleCasDesc, Consts.PESSIMISTIC) match {
                case McasStatus.Successful =>
                  // ok, we passed back the panic to the other side
                case Version.Reserved =>
                  impossible("tryPerform returned Reserved when it was called with PESSIMISTIC")
                case mcasResult =>
                  _assert((mcasResult == McasStatus.FailedVal) || this.mcas.hasVersionFailure)
                  // the other side rescinded before we could pass back
                  // the panic to it; we can't do anything with that
              }
              // in any case, we can't go on with the exchange due to the panic, so we retry:
              retry()
            case Right(_) =>
              val otherDesc = ctx.addCasFromInitial(desc, c.hole.loc, null, fx).toImmutable
              c.mergeDescs.otherDesc = otherDesc // pass it forward
              desc = c.selfDesc
              if (otherDesc.validTs > desc.validTs) {
                if (forceValidate(null)) {
                  next()
                } else {
                  retry()
                }
              } else {
                next()
              }
          }
          //println(s"FinishExchange: our result is '${a}' - thread#${Thread.currentThread().getId()}")
          loop(nxt)
        case c: MergeDescs =>
          val selfDesc = descImm
          val otherDesc = c.otherDesc
          _assert(otherDesc ne null)
          contT.push(RxnConsts.ContAndThen)
          val nxt = try {
            ctx.addAll(selfDesc, otherDesc, canExtend = !this.hasTentativeRead) match {
              case null => // can't extend
                clearDesc()
                retry()
              case mergedDesc =>
                desc = mergedDesc
                next()
            }
          } catch {
            case _: IllegalInsertException =>
              //println("can't merge overlapping descriptors")
              clearDesc()
              retry()
          }
          loop(nxt)
        case c: TicketRead[a] =>
          val hwd = readMaybeFromLog(c.ref)
          if (hwd eq null) {
            loop(retry())
          } else {
            a = new unsafe.TicketForTicketRead[a](hwd)
            loop(next())
          }
        case _: ForceValidate => // ForceValidate
          if (forceValidate(optHwd = null)) {
            a = () : Any
            loop(next())
          } else {
            loop(retry())
          }
        case c: Pure[_] => // Pure
          a = c.a
          loop(next())
        case c: ProductR[_, _] => // ProductR
          contT.push(RxnConsts.ContProductR)
          contK.push2(c.right, a)
          loop(c.left)
        case c: FlatMap[_, _, _] => // FlatMap
          contT.push(RxnConsts.ContFlatMap)
          contK.push2(a, c.f)
          loop(c.rxn)
        case c: Flatten[_] =>
          contT.push(RxnConsts.ContFlatten)
          loop(c.rxn)
        case _: SuspendUntil => // SuspendUntil
          _assert(this.canSuspend)
          // user code can't access a `SuspendUntil`, so
          // we can abuse `R` and return `SuspendUntil`:
          curr.asInstanceOf[R]
        case c: TailRecM[_, _] => // TailRecM
          val f = c.f
          val nxt = f(c.a)
          contT.push(RxnConsts.ContTailRecM)
          contK.push2(f, a)
          loop(nxt)
        case c: Map_[_, _] => // Map_
          contT.push(RxnConsts.ContMap)
          contK.push(c.f)
          loop(c.rxn)
        case c: OrElse[_] => // STM
          saveStmAlt(c.right)
          contT.push(RxnConsts.ContOrElse)
          loop(c.left)
        case c: Unread[_] => // Unread
          if ((_desc ne null) && desc.nonEmpty) {
            val loc = c.ref.loc
            desc = desc.removeReadOnlyRef(loc) // throws if not RO ref; NOP if it doesn't contain the ref
          } // else: empty log, nothing to do
          a = ()
          loop(next())
        case c: LocalNewEnd => // LocalNewEnd
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
        case c: TentativeRead[_] => // TentativeRead
          val hwd = tentativeRead(c.loc)
          val nxt = if (hwd eq null) {
            retry()
          } else {
            a = hwd.nv
            next()
          }
          loop(nxt)
      }
    }

    final def interpretAsync[F[_]](poll: F ~> F)(implicit F: Async[F]): F[R] = {
      if (this.canSuspend) {
        // cede or sleep strategy:
        def step(ctxHint: Mcas.ThreadContext, debugNext: Rxn[Any]): F[R] = F.defer {
          val ctx = if ((ctxHint ne null) && mcas.isCurrentContext(ctxHint)) {
            ctxHint
          } else {
            mcas.currentContext()
          }
          this.ctx = ctx
          try {
            loop(if (debugNext eq null) startRxn else debugNext) match {
              case s: SuspendUntil =>
                this.beforeSuspend()
                val sus: F[Rxn[Any]] = s.toF[F](mcas, ctx)
                F.flatMap(poll(sus)) { nxt => step(ctxHint = ctx, debugNext = nxt) }
              case r =>
                this.beforeResult()
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

    // Unsafe/imperative API (`InRxn`):

    final override def currentContext(): Mcas.ThreadContext = {
      this.ctx match {
        case null =>
          this.mcas.currentContext()
        case ctx =>
          ctx
      }
    }

    final override def initCtx(c: Mcas.ThreadContext): Unit = {
      this.ctx match {
        case null =>
          this.ctx = c
        case _ =>
          throw new IllegalStateException("ctx is already initialized")
      }
    }

    final override def imperativeRetry(): Option[unsafe2.CanSuspendInF] = {
      this.retry() match {
        case null =>
          // atomically/atomicallyInAsync has `null` as `startRxn` (and no
          // post-commit actions for now), so this means a full retry after spinning:
          None
        case s: SuspendUntil =>
          Some(s)
        case xyz =>
          // this shouldn't happen, because atomically/atomicallyInAsync has no alts (for now):
          impossible(s"retry (called in imperativeRetry) returned $xyz")
      }
    }

    final override def readRef[A](ref: MemoryLocation[A]): A = {
      _assert(this._entryHolder eq null) // just to be sure
      desc = desc.computeIfAbsent(ref.cast[Any], tok = ref.asInstanceOf[Rxn[Any]], visitor = this)
      val hwd = this._entryHolder.cast[A]
      this._entryHolder = null // cleanup
      val hwd2 = revalidateIfNeeded(hwd)
      if (hwd2 eq null) { // need to roll back
        _assert(this._desc eq null)
        throw unsafe2.RetryException.notPermanentFailure
      } else {
        hwd2.nv
      }
    }

    final override def writeRef[A](ref: MemoryLocation[A], nv: A): Unit = {
      val c = new Rxn.UpdSet1(ref, nv)
      if (!handleUpd(c)) {
        throw unsafe2.RetryException.notPermanentFailure
      }
    }

    final override def updateRef[A](ref: MemoryLocation[A], f: A => A): Unit = {
      val c = new Rxn.UpdUpdate1(ref, f)
      if (!handleUpd(c)) {
        throw unsafe2.RetryException.notPermanentFailure
      }
    }

    final override def getAndSetRef[A](ref: MemoryLocation[A], nv: A): A = {
      val c = new Rxn.UpdFull(ref, { (ov: A) => (nv, ov) })
      if (!handleUpd(c)) {
        throw unsafe2.RetryException.notPermanentFailure
      } else {
        // Note: this.a is garbage now, but will be
        // overwritten with the result immediately
        // when `embedUnsafe` ends.
        aCastTo[A]
      }
    }

    final override def imperativeTentativeRead[A](ref: MemoryLocation[A]): A = {
      val hwd = tentativeRead(ref)
      if (hwd eq null) {
        throw unsafe2.RetryException.notPermanentFailure
      } else {
        hwd.nv
      }
    }

    final override def imperativeTicketRead[A](ref: MemoryLocation[A]): unsafe2.Ticket[A] = {
      val hwd = readMaybeFromLog(ref)
      if (hwd eq null) {
        throw unsafe2.RetryException.notPermanentFailure
      } else {
        unsafe2.Ticket[A](hwd)
      }
    }

    final override def imperativeTicketWrite[A](hwd: LogEntry[A], newest: A): Unit = {
      val c = new Rxn.TicketWrite(hwd, newest)
      if (!ticketWrite(c)) {
        _assert(this._desc eq null)
        throw unsafe2.RetryException.notPermanentFailure
      }
    }

    final override def imperativePostCommit(pca: Rxn[Unit]): Unit = {
      pc.push(pca)
    }

    final override def imperativeCommit(): Boolean = {
      val ok = handleCommit()
      _assert(pc.isEmpty()) // imperative API has no post-commit actions (for now)
      ok
    }

    @inline
    private[choam] final override def beforeSuspend(): Unit = {
      _assert(this._entryHolder eq null)
    }

    @inline
    private[choam] final override def beforeResult(): Unit = {
      _assert(
        (this._entryHolder eq null) &&
        ((this.locals eq null) || this.locals.isEmpty())
      )
    }
  }
}

private[core] sealed abstract class RxnInstances0 extends RxnInstances1 { this: Rxn.type =>
}

private sealed abstract class RxnInstances1 extends RxnInstances2 { self: Rxn.type =>
}

private sealed abstract class RxnInstances2 extends RxnInstances3 { this: Rxn.type =>

  // Even though we override `tailRecM`, we still
  // inherit `StackSafeMonad`, in case someone
  // somewhere uses that as a marker or even a
  // typeclass:
  implicit final def monadInstance: StackSafeMonad[Rxn] =
    _monadInstance

  private[this] val _monadInstance: StackSafeMonad[Rxn] = new StackSafeMonad[Rxn] {
    final override def unit: Rxn[Unit] =
      Rxn.unit
    final override def pure[A](a: A): Rxn[A] =
      Rxn.pure(a)
    final override def point[A](a: A): Rxn[A] =
      Rxn.pure(a)
    final override def as[A, B](fa: Rxn[A], b: B): Rxn[B] =
      fa.as(b)
    final override def void[A](fa: Rxn[A]): Rxn[Unit] =
      fa.void
    final override def map[A, B](fa: Rxn[A])(f: A => B): Rxn[B] =
      fa.map(f)
    final override def map2[A, B, Z](fa: Rxn[A], fb: Rxn[B])(f: (A, B) => Z): Rxn[Z] =
      fa.map2(fb)(f)
    final override def productR[A, B](fa: Rxn[A])(fb: Rxn[B]): Rxn[B] =
      fa.productR(fb)
    final override def product[A, B](fa: Rxn[A], fb: Rxn[B]): Rxn[(A, B)] =
      fa.product(fb)
    final override def flatMap[A, B](fa: Rxn[A])(f: A => Rxn[B]): Rxn[B] =
      fa.flatMap(f)
    final override def tailRecM[A, B](a: A)(f: A => Rxn[Either[A, B]]): Rxn[B] =
      Rxn.tailRecM[A, B](a)(f)
  }
}

private sealed abstract class RxnInstances3 extends RxnInstances4 { self: Rxn.type =>

  implicit final def uniqueInstance: Unique[Rxn] =
    _uniqueInstance.asInstanceOf[Unique[Rxn]]

  private[this] val _uniqueInstance: Unique[Rxn] = new Unique[Rxn] {
    final override def applicative: Applicative[Rxn] =
      self.monadInstance
    final override def unique: Rxn[Unique.Token] =
      self.unique
  }
}

private sealed abstract class RxnInstances4 extends RxnInstances5 { this: Rxn.type =>
}

private sealed abstract class RxnInstances5 extends RxnInstances6 { this: Rxn.type =>

  /** Not implicit, because it would conflict with [[monoidInstance]]. */
  final def choiceSemigroup[B]: Semigroup[Rxn[B]] =
    _choiceSemigroup.asInstanceOf[Semigroup[Rxn[B]]]

  private[this] val _choiceSemigroup: Semigroup[Rxn[Any]] = new Semigroup[Rxn[Any]] {
    final override def combine(x: Rxn[Any], y: Rxn[Any]): Rxn[Any] =
      x + y
  }

  implicit final def monoidInstance[B](implicit B: Monoid[B]): Monoid[Rxn[B]] = new Monoid[Rxn[B]] {
    final override def combine(x: Rxn[B], y: Rxn[B]): Rxn[B] =
      x.map2(y) { (b1, b2) => B.combine(b1, b2) }
    final override def empty: Rxn[B] =
      Rxn.pure(B.empty)
  }
}

private sealed abstract class RxnInstances6 extends RxnInstances7 { self: Rxn.type =>

  implicit final def deferInstance: Defer[Rxn] =
    _deferInstance.asInstanceOf[Defer[Rxn]]

  private[this] val _deferInstance: Defer[Rxn] = new Defer[Rxn] {
    final override def defer[A](fa: => Rxn[A]): Rxn[A] = Rxn.unsafe.suspend(fa)
    final override def fix[A](fn: Rxn[A] => Rxn[A]): Rxn[A] = {
      // Instead of a `lazy val` (like in the superclass), we just
      // do a rel/acq here, because we know exactly how `defer`
      // works, and know that `.elem` will be initialized before
      // we return from this method (and we don't want a `lazy val`
      // to block). However, we still need the fences, to make sure
      // that if the resulting `Rxn[X, A]` is published by a race,
      // there is an ordering between writing `ref.elem` and reading
      // it. More specifically, e.g., this scenario:
      //
      // THREAD #1:
      // val res = fn(...) // creates `Rxn` result
      // ref.elem = res // plain write (reference)
      // releaseFence()
      // ...
      // x = res // publish it without synchronization
      //
      // THREAD #2:
      // val rxn = x // read it without synchronization
      // ... // rxn.unsafePerform executes the `fa` in `defer`:
      // acquireFence()
      // ref.elem // plain read (reference)
      //
      // This way, the releaseFence synchronizes-with
      // the acquireFence. This causes the write to
      // `ref.elem` in thread #1 to happen-before the
      // read of `ref.elem` in thread #2.
      val ref = new scala.runtime.ObjectRef[Rxn[A]](null)
      val res = fn(defer {
        self.acquireFence()
        ref.elem
      })
      ref.elem = res
      self.releaseFence()
      res
    }
  }

  /**
   * This is like `deferInstance.fix`, just without the fences.
   *
   * We need this to conduct an experiment: can we actually
   * observe a problem without the rel/acq fences?
   *
   * The answer is yes (on ARM); see `FixSync`.
   */
  private[choam] final def deferFixWithoutFences[A](fn: Rxn[A] => Rxn[A]): Rxn[A] = {
    val ref = new scala.runtime.ObjectRef[Rxn[A]](null)
    val res = fn(deferInstance.defer {
      ref.elem
    })
    ref.elem = res
    res
  }
}

private sealed abstract class RxnInstances7 extends RxnInstances8 { self: Rxn.type =>

  implicit final def showInstance[B]: Show[Rxn[B]] =
    _showInstance.asInstanceOf[Show[Rxn[B]]]

  private[this] val _showInstance: Show[Rxn[Any]] = new Show[Rxn[Any]] {
    final override def show(rxn: Rxn[Any]): String = rxn match {
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

  implicit final def alignInstance: Align[Rxn] =
    _alignInstance.asInstanceOf[Align[Rxn]]

  private[this] val _alignInstance: Align[Rxn] = new Align[Rxn] {
    final override def functor: Functor[Rxn] =
      self.monadInstance
    final override def align[A, B](fa: Rxn[A], fb: Rxn[B]): Rxn[Ior[A, B]] = {
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

  implicit final def uuidGenInstance: UUIDGen[Rxn] =
    self._uuidGen

  private[this] val _uuidGen: UUIDGen[Rxn] = new UUIDGen[Rxn] {
    final override def randomUUID: Rxn[UUID] =
      newUuidImpl
  }
}

private sealed abstract class RxnInstances10 extends RxnInstances11 { self: Rxn.type =>

  import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS, MILLISECONDS }

  implicit final def clockInstance: Clock[Rxn] =
    _clockInstance

  private[this] val _clockInstance: Clock[Rxn] = new Clock[Rxn] {
    final override def applicative: Applicative[Rxn] =
      Rxn.monadInstance
    final override def monotonic: Rxn[FiniteDuration] =
      Rxn.unsafe.delay { FiniteDuration(System.nanoTime(), NANOSECONDS) }
    final override def realTime: Rxn[FiniteDuration] =
      Rxn.unsafe.delay { FiniteDuration(System.currentTimeMillis(), MILLISECONDS) }
  }
}

private sealed abstract class RxnInstances11 extends RxnSyntax0 { self: Rxn.type =>

  implicit final def catsRefMakeInstance: CatsRef.Make[Rxn] =
    _catsRefMakeInstance

  private[this] val _catsRefMakeInstance: CatsRef.Make[Rxn] = new CatsRef.Make[Rxn] {
    final override def refOf[A](a: A): Rxn[CatsRef[Rxn, A]] = {
      Ref.unpadded(initial = a).map { underlying =>
        new CatsRef[Rxn, A] {
          final override def get: Rxn[A] =
            underlying.get
          final override def set(a: A): Rxn[Unit] =
            underlying.set(a)
          final override def access: Rxn[(A, A => Rxn[Boolean])] = {
            underlying.get.map { ov =>
              val setter = { (nv: A) =>
                // TODO: can we relax this? Would `ticketRead` be safe?
                underlying.modify { cv => if (equ(cv, ov)) (nv, true) else (cv, false) }
              }
              (ov, setter)
            }
          }
          final override def tryUpdate(f: A => A): Rxn[Boolean] =
            this.update(f).maybe
          final override def tryModify[B](f: A => (A, B)): Rxn[Option[B]] =
            this.modify(f).attempt
          final override def update(f: A => A): Rxn[Unit] =
            underlying.update(f)
          final override def modify[B](f: A => (A, B)): Rxn[B] =
            underlying.modify(f)
          final override def tryModifyState[B](state: State[A, B]): Rxn[Option[B]] =
            underlying.tryModify { a => state.runF.flatMap(_(a)).value }
          final override def modifyState[B](state: State[A, B]): Rxn[B] =
            underlying.modify { a => state.runF.flatMap(_(a)).value }
        }
      }
    }
  }
}

private sealed abstract class RxnSyntax0 extends RxnSyntax1 { this: Rxn.type =>

  import scala.language.implicitConversions

  implicit final def rxnInvariantSyntax[A](self: Rxn[A]): Rxn.InvariantSyntax[A] =
    new Rxn.InvariantSyntax(self)
}

private sealed abstract class RxnSyntax1 extends RxnSyntax2 { this: Rxn.type =>
}

private sealed abstract class RxnSyntax2 extends RxnCompanionPlatform { this: Rxn.type =>
}
