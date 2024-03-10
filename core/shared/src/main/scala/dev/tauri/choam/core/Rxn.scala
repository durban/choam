/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.concurrent.duration._

import cats.{ Align, Applicative, Defer, Functor, StackSafeMonad, Monoid, MonoidK, Semigroup, Show }
import cats.arrow.ArrowChoice
import cats.data.{ Ior, State }
import cats.mtl.Local
import cats.effect.kernel.{ Async, Clock, Unique, Ref => CatsRef }
import cats.effect.std.{ Random, SecureRandom, UUIDGen }

import internal.mcas.{ MemoryLocation, Mcas, HalfWordDescriptor, McasStatus, Descriptor }

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

  import Rxn._

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

  final def + [X <: A, Y >: B](that: Rxn[X, Y]): Rxn[X, Y] =
    new Choice[X, Y](this, that)

  final def >>> [C](that: Rxn[B, C]): Rxn[A, C] =
    new AndThen[A, B, C](this, that)

  final def × [C, D](that: Rxn[C, D]): Rxn[(A, C), (B, D)] =
    new AndAlso[A, B, C, D](this, that)

  final def * [X <: A, C](that: Rxn[X, C]): Rxn[X, (B, C)] =
    (this × that).contramap[X](x => (x, x))

  final def ? : Rxn[A, Option[B]] =
    this.attempt

  final def attempt: Rxn[A, Option[B]] =
    this.map(Some(_)) + pure[Option[B]](None)

  final def maybe: Rxn[A, Boolean] =
    this.as(true) + pure(false)

  final def map[C](f: B => C): Rxn[A, C] =
    this >>> lift(f)

  final def as[C](c: C): Rxn[A, C] =
    new As(this, c)

  // old implementation with map:
  private[choam] final def asOld[C](c: C): Rxn[A, C] =
    this.map(_ => c)

  final def void: Rxn[A, Unit] =
    this.as(())

  // FIXME: do we need this?
  final def dup: Rxn[A, (B, B)] =
    this.map { b => (b, b) }

  final def contramap[C](f: C => A): Rxn[C, B] =
    lift(f) >>> this

  final def provide(a: A): Axn[B] =
    new Provide[A, B](this, a)

  // old implementation with contramap:
  private[choam] final def provideOld(a: A): Axn[B] =
    contramap[Any](_ => a)

  final def dimap[C, D](f: C => A)(g: B => D): Rxn[C, D] =
    this.contramap(f).map(g)

  final def toFunction: A => Axn[B] = { (a: A) =>
    this.provide(a)
  }

  final def map2[X <: A, C, D](that: Rxn[X, C])(f: (B, C) => D): Rxn[X, D] =
    (this * that).map(f.tupled)

  final def <* [X <: A, C](that: Rxn[X, C]): Rxn[X, B] =
    this.productL(that)

  final def productL [X <: A, C](that: Rxn[X, C]): Rxn[X, B] =
    (this * that).map(_._1)

  final def *> [X <: A, C](that: Rxn[X, C]): Rxn[X, C] =
    this.productR(that)

  final def productR[X <: A, C](that: Rxn[X, C]): Rxn[X, C] =
    new ProductR[X, B, C](this, that)

  final def first[C]: Rxn[(A, C), (B, C)] =
    this × identity[C]

  final def second[C]: Rxn[(C, A), (C, B)] =
    identity[C] × this

  final def flatMap[X <: A, C](f: B => Rxn[X, C]): Rxn[X, C] =
    new FlatMap(this, f)

  // TODO: Unoptimized impl.:
  private[choam] final def flatMapOld[X <: A, C](f: B => Rxn[X, C]): Rxn[X, C] = {
    val self: Rxn[X, (X, B)] = this.second[X].contramap[X](x => (x, x))
    val comp: Rxn[(X, B), C] = computed[(X, B), C](xb => f(xb._2).provide(xb._1))
    self >>> comp
  }

  final def flatMapF[C](f: B => Axn[C]): Rxn[A, C] =
    new FlatMapF(this, f)

  // TODO: Unoptimized impl.:
  private[choam] final def flatMapFOld[C](f: B => Axn[C]): Rxn[A, C] =
    this >>> computed(f)

  // TODO: optimize
  final def >> [X <: A, C](that: => Rxn[X, C]): Rxn[X, C] =
    this.flatMap { _ => that }

  final def flatTap(rxn: Rxn[B, Unit]): Rxn[A, B] =
    this.flatMapF { b => rxn.provide(b).as(b) }

  final def flatten[C](implicit ev: B <:< Axn[C]): Rxn[A, C] =
    this.flatMapF(ev)

  final def postCommit(pc: Rxn[B, Unit]): Rxn[A, B] =
    this >>> Rxn.postCommit[B](pc)

  /**
   * Execute the [[Rxn]] with the specified input `a`.
   *
   * This method is `unsafe` because it performs side-effects.
   *
   * @param a the input to the [[Rxn]].
   * @param mcas the [[internal.mcas.Mcas]] implementation to use.
   * @param strategy the retry strategy to use.
   * @return the result of the executed [[Rxn]].
   */
  final def unsafePerform(
    a: A,
    mcas: Mcas,
    strategy: Strategy.Spin = Strategy.Default,
  ): B = {
    new InterpreterState[A, B](
      rxn = this,
      x = a,
      mcas = mcas,
      strategy = strategy,
    ).interpretSync()
  }

  final def perform[F[_], X >: B](
    a: A,
    mcas: Mcas,
    strategy: Strategy = Strategy.Default,
  )(implicit F: Async[F]): F[X] = {
    new InterpreterState[A, X](
      this,
      a,
      mcas = mcas,
      strategy = strategy,
    ).interpretAsync(F)
  }

  /** Only for tests/benchmarks */
  private[choam] final def unsafePerformInternal(
    a: A,
    ctx: Mcas.ThreadContext,
    maxBackoff: Int = Rxn.defaultMaxSpin,
    randomizeBackoff: Boolean = Rxn.defaultRandomizeSpin,
  ): B = {
    // TODO: this allocation can hurt us in benchmarks!
    val str = Strategy
      .Default
      .withMaxSpin(maxBackoff)
      .withRandomizeSpin(randomizeBackoff)
    new InterpreterState[A, B](
      this,
      a,
      ctx.impl,
      strategy = str,
    ).interpretSyncWithContext(ctx)
  }

  override def toString: String
}

object Rxn extends RxnInstances0 {

  // TODO: move this to separate file, and rename to RetryStrategy(?)
  sealed abstract class Strategy extends Product with Serializable {

    // TODO: do we EVER want `randomize*` to be actually false?

    // SPIN:

    // maxRetries:

    def maxRetries: Option[Int]

    def withMaxRetries(maxRetries: Option[Int]): Strategy

    private[core] def maxRetriesInt: Int

    // maxSpin:

    def maxSpin: Int

    def withMaxSpin(maxSpin: Int): Strategy

    // randomizeSpin:

    def randomizeSpin: Boolean

    def withRandomizeSpin(randomizeSpin: Boolean): Strategy

    // CEDE:

    // maxCede:

    def maxCede: Int

    def withMaxCede(maxCede: Int): Strategy

    // randomizeCede:

    def randomizeCede: Boolean

    def withRandomizeCede(randomizeCede: Boolean): Strategy

    // SLEEP:

    // maxSleep:

    def maxSleep: FiniteDuration

    private[core] def maxSleepNanos: Long

    def withMaxSleep(maxSleep: FiniteDuration): Strategy

    // randomizeSleep:

    def randomizeSleep: Boolean

    def withRandomizeSleep(randomizeSleep: Boolean): Strategy

    // MISC.:

    private[core] def canSuspend: Boolean
  }

  final object Strategy {

    sealed abstract class Spin
      extends Strategy {

      override def withMaxRetries(maxRetries: Option[Int]): Spin

      override def withMaxSpin(maxSpin: Int): Spin

      override def withRandomizeSpin(randomizeSpin: Boolean): Spin

      private[core] final override def canSuspend: Boolean =
        false
    }

    private final case class StrategyFull(
      maxRetries: Option[Int],
      maxSpin: Int,
      randomizeSpin: Boolean,
      maxCede: Int,
      randomizeCede: Boolean,
      maxSleep: FiniteDuration,
      randomizeSleep: Boolean,
    ) extends Strategy {

      require(maxRetries.forall{ mr => (mr >= 0) && (mr < Integer.MAX_VALUE) })
      require(maxSpin > 0)
      require(maxCede >= 0)
      require(maxSleep >= Duration.Zero)
      require((maxCede > 0) || (maxSleep > Duration.Zero)) // otherwise it should be SPIN

      final override def withMaxRetries(maxRetries: Option[Int]): Strategy =
        this.copy(maxRetries = maxRetries)

      final override def withMaxSpin(maxSpin: Int): Strategy =
        this.copy(maxSpin = maxSpin)

      final override def withRandomizeSpin(randomizeSpin: Boolean): Strategy =
        this.copy(randomizeSpin = randomizeSpin)

      final override def withMaxCede(maxCede: Int): Strategy = {
        if ((maxCede == 0) && (this.maxSleepNanos == 0L)) {
          StrategySpin(
            maxRetries = this.maxRetries,
            maxSpin = this.maxSpin,
            randomizeSpin = this.randomizeSpin,
          )
        } else {
          this.copy(maxCede = maxCede)
        }
      }

      final override def withRandomizeCede(randomizeCede: Boolean): Strategy =
        this.copy(randomizeCede = randomizeCede)

      final override def withMaxSleep(maxSleep: FiniteDuration): Strategy = {
        if ((maxSleep == Duration.Zero) && (this.maxCede == 0)) {
          StrategySpin(
            maxRetries = this.maxRetries,
            maxSpin = this.maxSpin,
            randomizeSpin = this.randomizeSpin,
          )
        } else {
          this.copy(maxSleep = maxSleep)
        }
      }

      final override def withRandomizeSleep(randomizeSleep: Boolean): Strategy =
        this.copy(randomizeSleep = randomizeSleep)

      private[core] override val maxRetriesInt: Int = maxRetries match {
        case Some(n) => n
        case None => -1
      }

      private[core] override val maxSleepNanos: Long =
        maxSleep.toNanos

      private[core] final override def canSuspend: Boolean =
        true
    }

    private final case class StrategySpin(
      maxRetries: Option[Int],
      maxSpin: Int,
      randomizeSpin: Boolean,
    ) extends Spin {

      require(maxRetries.forall{ mr => (mr >= 0) && (mr < Integer.MAX_VALUE) })
      require(maxSpin > 0)

      final override def withMaxRetries(maxRetries: Option[Int]): Spin =
        this.copy(maxRetries = maxRetries)

      final override def withMaxSpin(maxSpin: Int): Spin = {
        if (maxSpin == this.maxSpin) this
        else this.copy(maxSpin = maxSpin)
      }

      final override def withRandomizeSpin(randomizeSpin: Boolean): Spin = {
        if (randomizeSpin == this.randomizeSpin) this
        else this.copy(randomizeSpin = randomizeSpin)
      }

      final override def withMaxCede(maxCede: Int): Strategy = {
        if (maxCede == 0) {
          this
        } else {
          StrategyFull(
            maxRetries = maxRetries,
            maxSpin = maxSpin,
            randomizeSpin = randomizeSpin,
            maxCede = maxCede,
            randomizeCede = defaultRandomizeCede,
            maxSleep = Duration.Zero,
            randomizeSleep = false,
          )
        }
      }

      final override def withRandomizeCede(randomizeCede: Boolean): Strategy = {
        if (randomizeCede) {
          StrategyFull(
            maxRetries = maxRetries,
            maxSpin = maxSpin,
            randomizeSpin = randomizeSpin,
            maxCede = defaultMaxCede,
            randomizeCede = true,
            maxSleep = Duration.Zero,
            randomizeSleep = false,
          )
        } else {
          this
        }
      }

      final override def withMaxSleep(maxSleep: FiniteDuration): Strategy = {
        if (maxSleep == Duration.Zero) {
          this
        } else {
          StrategyFull(
            maxRetries = maxRetries,
            maxSpin = maxSpin,
            randomizeSpin = randomizeSpin,
            maxCede = defaultMaxCede, // TODO: 0?
            randomizeCede = defaultRandomizeCede, // TODO: false?
            maxSleep = maxSleep,
            randomizeSleep = defaultRandomizeSleep,
          )
        }
      }

      final override def withRandomizeSleep(randomizeSleep: Boolean): Strategy = {
        if (randomizeSleep) {
          StrategyFull(
            maxRetries = maxRetries,
            maxSpin = maxSpin,
            randomizeSpin = randomizeSpin,
            maxCede = defaultMaxCede, // TODO: 0?
            randomizeCede = defaultRandomizeCede, // TODO: false?
            maxSleep = defaultMaxSleep,
            randomizeSleep = true,
          )
        } else {
          this
        }
      }

      private[core] override val maxRetriesInt: Int = maxRetries match {
        case Some(n) => n
        case None => -1
      }

      final override def maxCede: Int =
        0

      final override def randomizeCede: Boolean =
        false

      final override def maxSleep: FiniteDuration =
        Duration.Zero

      private[core] final override def maxSleepNanos: Long =
        0L

      final override def randomizeSleep: Boolean =
        false
    }

    final val Default: Spin =
      spin(maxRetries = None, maxSpin = defaultMaxSpin, randomizeSpin = defaultRandomizeSpin)

    final def sleep(
      maxRetries: Option[Int],
      maxSpin: Int,
      randomizeSpin: Boolean,
      maxCede: Int,
      randomizeCede: Boolean,
      maxSleep: FiniteDuration,
      randomizeSleep: Boolean,
    ): Strategy = {
      require(maxSleep > Duration.Zero)
      StrategyFull(
        maxRetries = maxRetries,
        maxSpin = maxSpin,
        randomizeSpin = randomizeSpin,
        maxCede = maxCede,
        randomizeCede = randomizeCede,
        maxSleep = maxSleep,
        randomizeSleep = randomizeSleep,
      )
    }

    final def cede(
      maxRetries: Option[Int],
      maxSpin: Int,
      randomizeSpin: Boolean,
      maxCede: Int,
      randomizeCede: Boolean,
    ): Strategy = {
      StrategyFull(
        maxRetries = maxRetries,
        maxSpin = maxSpin,
        randomizeSpin = randomizeSpin,
        maxCede = maxCede,
        randomizeCede = randomizeCede,
        maxSleep = Duration.Zero,
        randomizeSleep = false,
      )
    }

    final def spin(
      maxRetries: Option[Int],
      maxSpin: Int,
      randomizeSpin: Boolean,
    ): Spin = {
      StrategySpin(
        maxRetries = maxRetries,
        maxSpin = maxSpin,
        randomizeSpin = randomizeSpin,
      )
    }
  }

  private[this] final val interruptCheckPeriod =
    16384

  /*
   * The default value of 256 for `maxSpin` ensures that
   * there is at most 256 (or 512 with randomization) calls
   * to `onSpinWait` (see `Backoff`). It was determined
   * with experiments (see `SpinBench`), that under very
   * high contention, this increases performance (compared
   * to the previous value of 16.
   */
  private[core] final val defaultMaxSpin =
    256

  /*
   * `randomizeSpin` is true by default, since it seems
   * to have a small performance advantage for certain
   * operations (and no downside for others). See `SpinBench`.
   */
  private[Rxn] final val defaultRandomizeSpin =
    true

  private[core] final val defaultMaxCede =
    1 // TODO

  private[Rxn] final val defaultRandomizeCede =
    false // TODO

  private[core] final val defaultMaxSleep =
    100.millis // TODO

  private[Rxn] final val defaultRandomizeSleep =
    true

  /** This is just exporting `DefaultMcas`, because that's in an internal package */
  final def DefaultMcas: Mcas =
    Mcas.DefaultMcas

  // API:

  final def pure[A](a: A): Axn[A] =
    new Pure[A](a)

  /** Old name of `pure` */
  private[choam] final def ret[A](a: A): Axn[A] =
    pure(a)

  final def identity[A]: Rxn[A, A] =
    lift(a => a)

  final def lift[A, B](f: A => B): Rxn[A, B] =
    new Lift(f)

  final def unit[A]: Rxn[A, Unit] =
    pure(())

  final def computed[A, B](f: A => Axn[B]): Rxn[A, B] =
    new Computed(f)

  final def postCommit[A](pc: Rxn[A, Unit]): Rxn[A, A] =
    new PostCommit[A](pc)

  // Utilities:

  private[this] val _osRng: random.OsRng = {
    // Under certain circumstances (e.g.,
    // Linux right after boot in a
    // fresh VM), this call might block.
    // We really can't do anything about
    // it, but at least it's not during
    // executing a `Rxn` (it happens when
    // the very first `Rxn` is *created*,
    // and the `Rxn` class is loaded).
    random.OsRng.mkNew()
  }

  private[core] final def osRng: random.OsRng =
    _osRng

  private[this] val _fastRandom: Random[Axn] =
    random.newFastRandom

  private[this] val _secureRandom: SecureRandom[Axn] =
    random.newSecureRandom(_osRng)

  final def unique: Axn[Unique.Token] =
    unsafe.delay { _ => new Unique.Token() }

  final def fastRandom: Random[Axn] =
    _fastRandom

  final def secureRandom: SecureRandom[Axn] =
    _secureRandom

  final def deterministicRandom(initialSeed: Long): Axn[random.SplittableRandom[Axn]] =
    random.deterministicRandom(initialSeed)

  private[choam] final object ref {

    private[choam] final def get[A](r: Ref[A]): Axn[A] =
      new Read(r.loc)

    private[choam] final def upd[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): Rxn[B, C] =
      new Upd(r.loc, f)

    private[choam] final def updWith[A, B, C](r: Ref[A])(f: (A, B) => Axn[(A, C)]): Rxn[B, C] =
      new UpdWith[A, B, C](r.loc, f)
  }

  final object unsafe {

    sealed abstract class Ticket[A] {
      def unsafePeek: A
      def unsafeSet(nv: A): Axn[Unit]
      def unsafeIsReadOnly: Boolean
      final def unsafeValidate: Axn[Unit] =
        this.unsafeSet(this.unsafePeek)
    }

    private[Rxn] final class TicketImpl[A](hwd: HalfWordDescriptor[A])
      extends Ticket[A] {

      final def unsafePeek: A =
        hwd.nv

      final def unsafeSet(nv: A): Axn[Unit] =
        new TicketWrite(hwd, nv)

      final def unsafeIsReadOnly: Boolean =
        hwd.readOnly
    }

    private[choam] final def directRead[A](r: Ref[A]): Axn[A] =
      new DirectRead[A](r.loc)

    def ticketRead[A](r: Ref[A]): Axn[unsafe.Ticket[A]] =
      new TicketRead[A](r.loc)

    private[choam] final def cas[A](r: Ref[A], ov: A, nv: A): Axn[Unit] =
      new Cas[A](r.loc, ov, nv)

    def retry[A, B]: Rxn[A, B] =
      new AlwaysRetry[A, B]

    private[choam] def delay[A, B](uf: A => B): Rxn[A, B] =
      lift(uf)

    private[choam] def suspend[A, B](uf: A => Axn[B]): Rxn[A, B] =
      delay(uf).flatten // TODO: optimize

    private[choam] def delayContext[A](uf: Mcas.ThreadContext => A): Axn[A] =
      context(uf)

    // TODO: NB: this is also like `delay`
    // TODO: Calling `unsafePerform` (or similar) inside
    // TODO: `uf` is dangerous; currently it only messes
    // TODO: up exchanger statistics; in the future, who knows...
    private[choam] def context[A](uf: Mcas.ThreadContext => A): Axn[A] =
      new Ctx[A](uf)

    private[choam] def suspendContext[A](uf: Mcas.ThreadContext => Axn[A]): Axn[A] =
      this.context(uf).flatten // TODO: optimize

    final def exchanger[A, B]: Axn[Exchanger[A, B]] =
      Exchanger.apply[A, B]

    private[choam] final def exchange[A, B](ex: Exchanger[A, B]): Rxn[A, B] =
      ex.exchange

    /**
     * This is not unsafe by itself, but it is only useful
     * if there are other unsafe things going on (validation
     * is handled automatically otherwise). This is why it
     * is part of the `unsafe` API.
     */
    final def forceValidate: Axn[Unit] =
      new ForceValidate
  }

  private[core] final object internal {

    final def exchange[A, B](ex: ExchangerImpl[A, B]): Rxn[A, B] =
      new Exchange[A, B](ex)

    final def finishExchange[D](
      hole: Ref[Exchanger.NodeResult[D]],
      restOtherContK: ObjStack.Lst[Any],
      lenSelfContT: Int,
    ): Rxn[D, Unit] = new FinishExchange(hole, restOtherContK, lenSelfContT)
  }

  // Representation:

  /** Only the interpreter can use this! */
  private final class Commit[A]() extends Rxn[A, A] {
    private[core] final override def tag = 0
    final override def toString: String = "Commit()"
  }

  private final class AlwaysRetry[A, B]() extends Rxn[A, B] {
    private[core] final override def tag = 1
    final override def toString: String = "AlwaysRetry()"
  }

  private final class PostCommit[A](val pc: Rxn[A, Unit]) extends Rxn[A, A] {
    private[core] final override def tag = 2
    final override def toString: String = s"PostCommit(${pc})"
  }

  private final class Lift[A, B](val func: A => B) extends Rxn[A, B] {
    private[core] final override def tag = 3
    final override def toString: String = "Lift(<function>)"
  }

  private final class Computed[A, B](val f: A => Axn[B]) extends Rxn[A, B] {
    private[core] final def tag = 4
    final override def toString: String = "Computed(<function>)"
  }

  // tag = 5 (unused)

  private final class Choice[A, B](val left: Rxn[A, B], val right: Rxn[A, B]) extends Rxn[A, B] {
    private[core] final override def tag = 6
    final override def toString: String = s"Choice(${left}, ${right})"
  }

  private final class Cas[A](val ref: MemoryLocation[A], val ov: A, val nv: A) extends Rxn[Any, Unit] {
    private[core] final override def tag = 7
    final override def toString: String = s"Cas(${ref}, ${ov}, ${nv})"
  }

  private final class Upd[A, B, X](val ref: MemoryLocation[X], val f: (X, A) => (X, B)) extends Rxn[A, B] {
    private[core] final override def tag = 8
    final override def toString: String = s"Upd(${ref}, <function>)"
  }

  private final class DirectRead[A](val ref: MemoryLocation[A]) extends Rxn[Any, A] {
    private[core] final override def tag = 9
    final override def toString: String = s"DirectRead(${ref})"
  }

  private final class Exchange[A, B](val exchanger: ExchangerImpl[A, B]) extends Rxn[A, B] {
    private[core] final override def tag = 10
    final override def toString: String = s"Exchange(${exchanger})"
  }

  private final class AndThen[A, B, C](val left: Rxn[A, B], val right: Rxn[B, C]) extends Rxn[A, C] {
    private[core] final override def tag = 11
    final override def toString: String = s"AndThen(${left}, ${right})"
  }

  private final class AndAlso[A, B, C, D](val left: Rxn[A, B], val right: Rxn[C, D]) extends Rxn[(A, C), (B, D)] {
    private[core] final override def tag = 12
    final override def toString: String = s"AndAlso(${left}, ${right})"
  }

  /** Only the interpreter can use this! */
  private final class Done[A](val result: A) extends Rxn[Any, A] {
    private[core] final override def tag = 13
    final override def toString: String = s"Done(${result})"
  }

  private final class Ctx[A](val uf: Mcas.ThreadContext => A) extends Rxn[Any, A] {
    private[core] final override def tag = 14
    final override def toString: String = s"Ctx(<block>)"
  }

  private final class Provide[A, B](val rxn: Rxn[A, B], val a: A) extends Rxn[Any, B] {
    private[core] final override def tag = 15
    final override def toString: String = s"Provide(${rxn}, ${a})"
  }

  private final class UpdWith[A, B, C](val ref: MemoryLocation[A], val f: (A, B) => Axn[(A, C)]) extends Rxn[B, C] {
    private[core] final override def tag = 16
    final override def toString: String = s"UpdWith(${ref}, <function>)"
  }

  private final class As[A, B, C](val rxn: Rxn[A, B], val c: C) extends Rxn[A, C] {
    private[core] final override def tag = 17
    final override def toString: String = s"As(${rxn}, ${c})"
  }

  /** Only the interpreter/exchanger can use this! */
  private final class FinishExchange[D](
    val hole: Ref[Exchanger.NodeResult[D]],
    val restOtherContK: ObjStack.Lst[Any],
    val lenSelfContT: Int,
  ) extends Rxn[D, Unit] {
    private[core] final override def tag = 18
    final override def toString: String = {
      val rockLen = ObjStack.Lst.length(this.restOtherContK)
      s"FinishExchange(${hole}, <ObjStack.Lst of length ${rockLen}>, ${lenSelfContT})"
    }
  }

  private final class Read[A](val ref: MemoryLocation[A]) extends Rxn[Any, A] {
    private[core] final override def tag = 19
    final override def toString: String = s"Read(${ref})"
  }

  private final class TicketRead[A](val ref: MemoryLocation[A]) extends Rxn[Any, unsafe.Ticket[A]] {
    private[core] final override def tag = 20
    final override def toString: String = s"TicketRead(${ref})"
  }

  private final class TicketWrite[A](val hwd: HalfWordDescriptor[A], val newest: A) extends Rxn[Any, Unit] {
    private[core] final override def tag = 21
    final override def toString: String = s"TicketWrite(${hwd}, ${newest})"
  }

  private final class ForceValidate() extends Rxn[Any, Unit] {
    private[core] final override def tag = 22
    final override def toString: String = s"ForceValidate()"
  }

  private final class Pure[A](val a: A) extends Rxn[Any, A] {
    private[core] final override def tag = 23
    final override def toString: String = s"Pure(${a})"
  }

  private final class ProductR[A, B, C](val left: Rxn[A, B], val right: Rxn[A, C]) extends Rxn[A, C] {
    private[core] final override def tag = 24
    final override def toString: String = s"ProductR(${left}, ${right})"
  }

  private final class FlatMapF[A, B, C](val rxn: Rxn[A, B], val f: B => Axn[C]) extends Rxn[A, C] {
    private[core] final override def tag = 25
    final override def toString: String = s"FlatMapF(${rxn}, <function>)"
  }

  private final class FlatMap[A, B, C](val rxn: Rxn[A, B], val f: B => Rxn[A, C]) extends Rxn[A, C] {
    private[core] final override def tag = 26
    final override def toString: String = s"FlatMap(${rxn}, <function>)"
  }

  // Interpreter:

  private[this] def newStack[A]() = {
    new ObjStack[A]
  }

  private[this] final val ContAndThen = 0.toByte
  private[this] final val ContAndAlso = 1.toByte
  private[this] final val ContAndAlsoJoin = 2.toByte
  // 3.toByte is unused
  private[this] final val ContPostCommit = 4.toByte
  private[this] final val ContAfterPostCommit = 5.toByte // TODO: rename(?)
  private[this] final val ContCommitPostCommit = 6.toByte
  private[this] final val ContUpdWith = 7.toByte
  private[this] final val ContAs = 8.toByte
  private[this] final val ContProductR = 9.toByte
  private[this] final val ContFlatMapF = 10.toByte
  private[this] final val ContFlatMap = 11.toByte

  private[this] final class PostCommitResultMarker // TODO: make this a java enum?
  private[this] final val postCommitResultMarker =
    new PostCommitResultMarker

  private[core] final val commitSingleton: Rxn[Any, Any] = // TODO: make this a java enum?
    new Commit[Any]

  private[this] final class Suspend // TODO: make this a java enum?
  private[this] final val Suspend =
    new Suspend

  private[this] final def suspend[X](): X =
    Suspend.asInstanceOf[X]

  final class MaxRetriesReached(val maxRetries: Int)
    extends Exception(s"reached maxRetries of ${maxRetries}")

  private final class InterpreterState[X, R](
    rxn: Rxn[X, R],
    x: X,
    mcas: Mcas,
    strategy: Rxn.Strategy,
  ) {

    private[this] val maxRetries: Int =
      strategy.maxRetriesInt

    private[this] val canSuspend: Boolean = {
      val cs = strategy.canSuspend
      assert((!cs) == strategy.isInstanceOf[Rxn.Strategy.Spin]) // just to be sure
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

    private[this] var _desc: Descriptor =
      null

    private[this] final def desc: Descriptor = {
      if (_desc ne null) {
        _desc
      } else {
        _desc = ctx.start()
        _desc
      }
    }

    @inline
    private[this] final def desc_=(d: Descriptor): Unit = {
      require(d ne null) // we want to be explicit, see `clearDesc`
      _desc = d
    }

    @inline
    private[this] final def clearDesc(): Unit = {
      _desc = null
    }

    private[this] val alts: ObjStack[Any] = newStack[Any]()

    private[this] val contT: ByteStack = new ByteStack(initSize = 8)
    private[this] val contK: ObjStack[Any] = newStack[Any]()
    private[this] val pc: ObjStack[Rxn[Any, Unit]] = newStack[Rxn[Any, Unit]]()
    private[this] val commit = commitSingleton
    contT.push(ContAfterPostCommit)
    contT.push(ContAndThen)
    contK.push(commit)

    private[this] var contTReset: Array[Byte] = contT.takeSnapshot()
    private[this] var contKReset: ObjStack.Lst[Any] = contK.takeSnapshot()

    private[this] var a: Any =
      x

    private[this] var fullRetries: Int =
      0

    private[this] var _stats: ExStatMap =
      null

    private[this] final def stats: ExStatMap = {
      val s = this._stats
      if (s eq null) {
        val s2 = this.ctx.getStatisticsPlain().asInstanceOf[ExStatMap]
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
          this.ctx.setStatisticsPlain(s.asInstanceOf[Map[AnyRef, AnyRef]])
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
      contKReset = contK.takeSnapshot()
    }

    private[this] final def resetConts(): Unit = {
      contT.loadSnapshot(contTReset)
      contK.loadSnapshot(contKReset)
    }

    private[this] final def clearAlts(): Unit = {
      alts.clear()
    }

    private[this] final def saveAlt(k: Rxn[Any, R]): Unit = {
      val alts = this.alts
      alts.push(ctx.snapshot(_desc))
      alts.push(a)
      alts.push(contT.takeSnapshot())
      alts.push(contK.takeSnapshot())
      alts.push(pc.takeSnapshot())
      alts.push(k)
    }

    private[this] final def loadAlt(): Rxn[Any, R] = {
      val alts = this.alts
      val res = alts.pop().asInstanceOf[Rxn[Any, R]]
      pc.loadSnapshotUnsafe(alts.pop().asInstanceOf[ObjStack.Lst[Any]])
      contK.loadSnapshot(alts.pop().asInstanceOf[ObjStack.Lst[Any]])
      contT.loadSnapshot(alts.pop().asInstanceOf[Array[Byte]])
      a = alts.pop()
      _desc = alts.pop().asInstanceOf[Descriptor]
      res
    }

    private[this] final def loadAltFrom(msg: Exchanger.Msg): Rxn[Any, R] = {
      pc.loadSnapshot(msg.postCommit)
      contK.loadSnapshot(msg.contK)
      contT.loadSnapshot(msg.contT)
      a = msg.value
      desc = msg.desc
      next().asInstanceOf[Rxn[Any, R]]
    }

    private[this] final def popFinalResult(): Any = {
      val r = contK.pop()
      assert(!equ(r, postCommitResultMarker))
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
        case 3 => // was ContAfterDelayComp
          impossible("Unknown contT: 3")
        case 4 => // ContPostCommit
          val pcAction = contK.pop().asInstanceOf[Rxn[Any, Any]]
          clearAlts()
          setContReset()
          a = () : Any
          startA = () : Any
          startRxn = pcAction
          this.fullRetries = 0
          clearDesc()
          pcAction
        case 5 => // ContAfterPostCommit
          val res = popFinalResult()
          assert(contK.isEmpty)
          assert(contT.isEmpty, s"contT is not empty: ${contT.toString}") // TODO: remove logging
          new Done(res)
        case 6 => // ContCommitPostCommit
          a = postCommitResultMarker : Any
          commit.asInstanceOf[Rxn[Any, Any]]
        case 7 => // ContUpdWith
          val ox = contK.pop()
          val ref = contK.pop().asInstanceOf[MemoryLocation[Any]]
          val (nx, res) = a.asInstanceOf[Tuple2[_, _]]
          val hwd = desc.getOrElseNull(ref)
          assert(hwd ne null)
          if (equ(hwd.nv, ox)) {
            desc = desc.overwrite(hwd.withNv(nx))
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
        case ct => // mustn't happen
          throw new UnsupportedOperationException(
            s"Unknown contT: ${ct}"
          )
      }
    }

    private[this] final def retry(): Rxn[Any, Any] =
      this.retry(this.canSuspend)

    private[this] final def retry(canSuspend: Boolean): Rxn[Any, Any] = {
      if (alts.nonEmpty) {
        // we're not actually retrying,
        // just going to the other side
        // of a `+` (so we're not
        // incrementing `fullRetries`):
        loadAlt()
      } else {
        // really retrying:
        val retriesNow = this.fullRetries + 1
        this.fullRetries = retriesNow
        val mr = this.maxRetries
        if ((mr >= 0) && ((retriesNow > mr) || (retriesNow == Integer.MAX_VALUE))) {
          // TODO: maybe we could represent "infinity" with MAX_VALUE instead of -1?
          throw new MaxRetriesReached(mr)
        } else {
          maybeCheckInterrupt(retriesNow)
        }
        // restart everything:
        clearDesc()
        a = startA
        resetConts()
        pc.clear()
        backoffAndNext(retriesNow, canSuspend)
      }
    }

    private[this] final def backoffAndNext(retries: Int, canSuspend: Boolean): Rxn[Any, Any] = {
      if (canSuspend) {
        null // we'll suspend (cede or sleep)
        // TODO: first try to spin
      } else {
        this.spin(retries)
        this.startRxn
      }
    }

    private[this] final def spin(retries: Int): Unit = {
      val strategy = this.strategy
      if (strategy.randomizeSpin) {
        Backoff.backoffRandom(retries, strategy.maxSpin, ctx.random)
      } else {
        Backoff.backoffConst(retries, strategy.maxSpin)
      }
    }

    private[this] final def nextSleep[F[_]]()(implicit F: Async[F]): F[Unit] = {
      require(canSuspend)
      val strategy = this.strategy
      if (strategy.randomizeSleep) {
        Backoff.sleepRandom(this.fullRetries, strategy.maxSleepNanos, ctx.random)
      } else {
        Backoff.sleepConst(this.fullRetries, strategy.maxSleepNanos)
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
    private[this] final def readMaybeFromLog[A](ref: MemoryLocation[A]): HalfWordDescriptor[A] = {
      desc.getOrElseNull(ref) match {
        case null =>
          // not in log
          revalidateIfNeeded(ctx.readIntoHwd(ref))
        case hwd =>
          hwd
      }
    }

    private[this] final def revalidateIfNeeded[A](hwd: HalfWordDescriptor[A]): HalfWordDescriptor[A] = {
      require(hwd ne null)
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

    private[this] final def forceValidate(optHwd: HalfWordDescriptor[_]): Boolean = {
      ctx.validateAndTryExtend(desc, hwd = optHwd) match {
        case null =>
          // need to roll back
          clearDesc()
          false
        case newDesc =>
          // OK, it was extended
          desc = newDesc
          true
      }
    }

    private[this] final def performMcas(d: Descriptor): Boolean = {
      if (d ne null) {
        (ctx.tryPerform(d) == McasStatus.Successful)
        // `Succesful` is success; otherwise the result is:
        // - Either `McasStatus.FailedVal`, which means that
        //   (at least) one word had an unexpected value
        //   (so we can't commit), or unexpected version (so
        //    revalidation would vertainly fail).
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
          if (performMcas(d)) {
            // save retry statistics:
            ctx.recordCommit(
              fullRetries = this.fullRetries,
              mcasRetries = 0,
            )
            // ok, commit is done, but we still need to perform post-commit actions
            val res = a
            a = () : Any
            if (!equ(res, postCommitResultMarker)) {
              // final result, Done will need it:
              contK.push(res)
            }
            while (pc.nonEmpty) {
              // commits the post-commit action:
              contT.push(ContCommitPostCommit)
              // the post-commit action itself:
              contK.push(pc.pop())
              contT.push(ContPostCommit)
            }
            loop(next())
          } else {
            contK.push(commit)
            contT.push(ContAndThen)
            val r = retry()
            if (r ne null) loop(r) else suspend()
          }
        case 1 => // AlwaysRetry
          val r = retry()
          if (r ne null) loop(r) else suspend()
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
        case 5 => // (was DelayComputed)
          impossible(s"Unknown tag 5 for ${curr}")
        case 6 => // Choice
          val c = curr.asInstanceOf[Choice[A, B]]
          saveAlt(c.right.asInstanceOf[Rxn[Any, R]])
          loop(c.left)
        case 7 => // Cas
          val c = curr.asInstanceOf[Cas[Any]]
          val hwd = readMaybeFromLog(c.ref)
          if (hwd eq null) {
            val r = retry()
            if (r ne null) loop(r) else suspend()
          } else {
            val currVal = hwd.nv
            if (equ(currVal, c.ov)) {
              desc = desc.addOrOverwrite(hwd.withNv(c.nv))
              a = () : Unit
              loop(next())
            }
            else {
              val r = retry()
              if (r ne null) loop(r) else suspend()
            }
          }
        case 8 => // Upd
          val c = curr.asInstanceOf[Upd[A, B, Any]]
          val hwd = readMaybeFromLog(c.ref)
          if (hwd eq null) {
            val r = retry()
            if (r ne null) loop(r) else suspend()
          } else {
            val ox = hwd.nv
            val (nx, b) = c.f(ox, a.asInstanceOf[A])
            desc = desc.addOrOverwrite(hwd.withNv(nx))
            a = b
            loop(next())
          }
        case 9 => // DirectRead
          val c = curr.asInstanceOf[DirectRead[B]]
          a = ctx.readDirect(c.ref)
          loop(next())
        case 10 => // Exchange
          val c = curr.asInstanceOf[Exchange[A, B]]
          val msg = Exchanger.Msg(
            value = a,
            contK = contK.takeSnapshot(),
            contT = contT.takeSnapshot(),
            desc = desc,
            postCommit = pc.takeSnapshot(),
            exchangerData = stats,
          )
          c.exchanger.tryExchange(msg = msg, params = exParams, ctx = ctx) match {
            case Left(newStats) =>
              _stats = newStats
              // TODO: we're never suspending with
              // TODO: exchanger; should we?
              loop(retry(canSuspend = false))
            case Right(contMsg) =>
              _stats = contMsg.exchangerData
              loop(loadAltFrom(contMsg))
          }
        case 11 => // AndThen
          val c = curr.asInstanceOf[AndThen[A, _, B]]
          contT.push(ContAndThen)
          contK.push(c.right)
          loop(c.left)
        case 12 => // AndAlso
          val c = curr.asInstanceOf[AndAlso[_, _, _, _]]
          val xp = a.asInstanceOf[Tuple2[_, _]]
          // join:
          contT.push(ContAndAlsoJoin)
          // right:
          contT.push(ContAndAlso)
          contK.push(c.right)
          contK.push(xp._2)
          // left:
          a = xp._1
          loop(c.left)
        case 13 => // Done
          val c = curr.asInstanceOf[Done[R]]
          c.result
        case 14 => // Ctx
          val c = curr.asInstanceOf[Ctx[R]]
          a = c.uf(ctx)
          loop(next())
        case 15 => // Provide
          val c = curr.asInstanceOf[Provide[A, B]]
          a = c.a
          loop(c.rxn)
        case 16 => // UpdWith
          val c = curr.asInstanceOf[UpdWith[Any, Any, _]]
          val hwd = readMaybeFromLog(c.ref)
          if (hwd eq null) {
            val r = retry()
            if (r ne null) loop(r) else suspend()
          } else {
            val ox = hwd.nv
            val axn = c.f(ox, a)
            desc = desc.addOrOverwrite(hwd)
            contT.push(ContUpdWith)
            contK.push(c.ref)
            contK.push(ox)
            // TODO: if `axn` writes to the same ref, we'll throw (see above)
            loop(axn)
          }
        case 17 => // As
          val c = curr.asInstanceOf[As[_, _, _]]
          contT.push(ContAs)
          contK.push(c.c)
          loop(c.rxn)
        case 18 => // FinishExchange
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
          val otherContT = ByteStack.push(_otherContT, ContAndThen)
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
        case 19 => // Read
          val c = curr.asInstanceOf[Read[Any]]
          val hwd = readMaybeFromLog(c.ref)
          if (hwd eq null) {
            val r = retry()
            if (r ne null) loop(r) else suspend()
          } else {
            a = hwd.nv
            desc = desc.addOrOverwrite(hwd)
            loop(next())
          }
        case 20 => // TicketRead
          val c = curr.asInstanceOf[TicketRead[Any]]
          val hwd = readMaybeFromLog(c.ref)
          if (hwd eq null) {
            val r = retry()
            if (r ne null) loop(r) else suspend()
          } else {
            a = new unsafe.TicketImpl[Any](hwd)
            loop(next())
          }
        case 21 => // TicketWrite
          val c = curr.asInstanceOf[TicketWrite[Any]]
          a = () : Any
          desc.getOrElseNull(c.hwd.address) match {
            case null =>
              // not in log yet, we try to insert it:
              revalidateIfNeeded(c.hwd) match {
                case null =>
                  val r = retry()
                  if (r ne null) loop(r) else suspend()
                case hwd =>
                  desc = desc.add(hwd.withNv(c.newest))
                  loop(next())
              }
            case existingHwd =>
              // NB: throws if it was modified in the meantime.
              // NB: this does no validation! (TODO: is this a problem?)
              desc = desc.overwrite(existingHwd.tryMergeTicket(c.hwd, c.newest))
              loop(next())
          }
        case 22 => // ForceValidate
          if (forceValidate(optHwd = null)) {
            a = () : Any
            loop(next())
          } else {
            val r = retry()
            if (r ne null) loop(r) else suspend()
          }
        case 23 => // Pure
          val c = curr.asInstanceOf[Pure[Any]]
          a = c.a
          loop(next())
        case 24 => // ProductR
          val c = curr.asInstanceOf[ProductR[Any, Any, Any]]
          contT.push(ContProductR)
          contK.push(c.right)
          contK.push(a)
          loop(c.left)
        case 25 => // FlatMapF
          val c = curr.asInstanceOf[FlatMapF[Any, Any, Any]]
          contT.push(ContFlatMapF)
          contK.push(c.f)
          loop(c.rxn)
        case 26 => // FlatMap
          val c = curr.asInstanceOf[FlatMap[Any, Any, Any]]
          contT.push(ContFlatMap)
          contK.push(a)
          contK.push(c.f)
          loop(c.rxn)
        case t => // mustn't happen
          impossible(s"Unknown tag ${t} for ${curr}")
      }
    }

    final def interpretAsync[F[_]](implicit F: Async[F]): F[R] = {
      if (this.canSuspend) {
        // cede or sleep strategy:
        F.defer {
          this.ctx = mcas.currentContext()
          try {
            loop(startRxn) match {
              case _: Suspend =>
                F.flatMap(nextSleep[F]()) { _ => interpretAsync[F](F) }
              case r =>
                // TODO: we're cancelable here; is this a problem? (probably yes)
                F.pure(r)
            }
          } finally {
            this.saveStats()
            this.invalidateCtx()
          }
        }
      } else {
        // spin strategy, so not really async:
        F.delay {
          // note: this is uncancelable, unlike `defer(loop(...); pure(...))` above
          this.interpretSync()
        }
      }
    }

    final def interpretSync(): R = {
      interpretSyncWithContext(mcas.currentContext())
    }

    /** This is also called for tests/benchmarks by `unsafePerformInternal` above. */
    final def interpretSyncWithContext(ctx: Mcas.ThreadContext): R = {
      assert(!canSuspend)
      this.ctx = ctx
      val r = loop(startRxn)
      this.saveStats()
      this.invalidateCtx()
      r
    }
  }
}

private[core] sealed abstract class RxnInstances0 extends RxnInstances1 { this: Rxn.type =>

  implicit final def arrowChoiceInstance: ArrowChoice[Rxn] = new ArrowChoice[Rxn] {

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

  implicit final def localInstance[E]: Local[Rxn[E, *], E] = new Local[Rxn[E, *], E] {
    final override def applicative: Applicative[Rxn[E, *]] =
      self.monadInstance[E]
    final override def ask[E2 >: E]: Rxn[E, E2] =
      Rxn.identity[E]
    final override def local[A](fa: Rxn[E, A])(f: E => E): Rxn[E, A] =
      fa.contramap(f)
  }
}

private sealed abstract class RxnInstances2 extends RxnInstances3 { this: Rxn.type =>

  implicit final def monadInstance[X]: StackSafeMonad[Rxn[X, *]] = new StackSafeMonad[Rxn[X, *]] {
    final override def flatMap[A, B](fa: Rxn[X, A])(f: A => Rxn[X, B]): Rxn[X, B] =
      fa.flatMap(f)
    final override def pure[A](a: A): Rxn[X, A] =
      Rxn.pure(a)
  }
}

private sealed abstract class RxnInstances3 extends RxnInstances4 { self: Rxn.type =>

  implicit final def uniqueInstance[X]: Unique[Rxn[X, *]] = new Unique[Rxn[X, *]] {
    final override def applicative: Applicative[Rxn[X, *]] =
      self.monadInstance[X]
    final override def unique: Rxn[X, Unique.Token] =
      self.unique
  }
}

private sealed abstract class RxnInstances4 extends RxnInstances5 { this: Rxn.type =>
  implicit final def monoidKInstance: MonoidK[λ[a => Rxn[a, a]]] = {
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
  final def choiceSemigroup[A, B]: Semigroup[Rxn[A, B]] = new Semigroup[Rxn[A, B]] {
    final override def combine(x: Rxn[A, B], y: Rxn[A, B]): Rxn[A, B] =
      x + y
  }

  implicit final def monoidInstance[A, B](implicit B: Monoid[B]): Monoid[Rxn[A, B]] = new Monoid[Rxn[A, B]] {
    override def combine(x: Rxn[A, B], y: Rxn[A, B]): Rxn[A, B] = {
      (x * y).map { bb => B.combine(bb._1, bb._2) }
    }
    override def empty: Rxn[A, B] =
      Rxn.pure(B.empty)
  }
}

private sealed abstract class RxnInstances6 extends RxnInstances7 { self: Rxn.type =>
  implicit final def deferInstance[X]: Defer[Rxn[X, *]] = new Defer[Rxn[X, *]] {
    final override def defer[A](fa: => Rxn[X, A]): Rxn[X, A] =
      self.computed[X, A] { x => fa.provide(x) }
  }
}

private sealed abstract class RxnInstances7 extends RxnInstances8 { self: Rxn.type =>
  implicit final def showInstance[A, B]: Show[Rxn[A, B]] =
    Show.fromToString
}

private sealed abstract class RxnInstances8 extends RxnInstances9 { self: Rxn.type =>
  implicit final def alignInstance[X]: Align[Rxn[X, *]] = new Align[Rxn[X, *]] {
    final override def functor: Functor[Rxn[X, *]] =
      self.monadInstance[X]
    final override def align[A, B](fa: Rxn[X, A], fb: Rxn[X, B]): Rxn[X, Ior[A, B]] = {
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
    random.uuidGen(self.osRng)

  @deprecated("Don't use uuidGenWrapper, because it may block", since = "0.4")
  private[choam] final def uuidGenWrapper[X]: UUIDGen[Rxn[X, *]] = new UUIDGen[Rxn[X, *]] {
    final override def randomUUID = Rxn.unsafe.delay { _ => java.util.UUID.randomUUID() }
  }
}

private sealed abstract class RxnInstances10 extends RxnInstances11 { self: Rxn.type =>
  implicit final def clockInstance[X]: Clock[Rxn[X, *]] = new Clock[Rxn[X, *]] {
    final override def applicative: Applicative[Rxn[X, *]] =
      self.monadInstance[X]
    final override def monotonic: Rxn[X, FiniteDuration] =
      self.unsafe.delay { _ => System.nanoTime().nanoseconds }
    final override def realTime: Rxn[X, FiniteDuration] =
      self.unsafe.delay { _ => System.currentTimeMillis().milliseconds }
  }
}

private sealed abstract class RxnInstances11 extends RxnSyntax0 { self: Rxn.type =>
  implicit final def catsRefMakeInstance[X]: CatsRef.Make[Rxn[X, *]] = new CatsRef.Make[Rxn[X, *]] {
    final override def refOf[A](a: A): Rxn[X, CatsRef[Rxn[X, *], A]] = {
      refs.Ref.unpadded(initial = a).map { underlying =>
        new CatsRef[Rxn[X, *], A] {
          final override def get: Rxn[X, A] =
            underlying.get
          final override def set(a: A): Rxn[X, Unit] =
            underlying.set.provide(a)
          final override def access: Rxn[X, (A, A => Rxn[X, Boolean])] = {
            underlying.get.map { ov =>
              val setter = { (nv: A) =>
                // TODO: can we relax this? Would `ticketRead` be safe?
                underlying.modify { cv => if (equ(cv, ov)) (nv, true) else (cv, false) }
              }
              (ov, setter)
            }
          }
          final override def tryUpdate(f: A => A): Rxn[X, Boolean] =
            this.update(f).maybe
          final override def tryModify[B](f: A => (A, B)): Rxn[X, Option[B]] =
            this.modify(f).attempt
          final override def update(f: A => A): Rxn[X, Unit] =
            underlying.update(f)
          final override def modify[B](f: A => (A, B)): Rxn[X, B] =
            underlying.modify(f)
          final override def tryModifyState[B](state: State[A, B]): Rxn[X, Option[B]] =
            underlying.tryModify { a => state.runF.flatMap(_(a)).value }
          final override def modifyState[B](state: State[A, B]): Rxn[X, B] =
            underlying.modify { a => state.runF.flatMap(_(a)).value }
        }
      }
    }
  }
}

private sealed abstract class RxnSyntax0 extends RxnSyntax1 { this: Rxn.type =>
  implicit final class InvariantSyntax[A, B](private val self: Rxn[A, B]) {
    final def apply[F[_]](a: A)(implicit F: Reactive[F]): F[B] =
      F.apply(self, a)
  }
}

private sealed abstract class RxnSyntax1 extends RxnSyntax2 { this: Rxn.type =>

  implicit final class AxnSyntax[A](private val self: Axn[A]) {
    final def run[F[_]](implicit F: Reactive[F]): F[A] =
      F.run(self)
  }
}

private sealed abstract class RxnSyntax2 extends RxnCompanionPlatform { this: Rxn.type =>

  // FIXME: do we need this?
  implicit final class Tuple2RxnSyntax[A, B, C](private val self: Rxn[A, (B, C)]) {
    def left: Rxn[A, B] =
      self.map(_._1)
    def right: Rxn[A, C] =
      self.map(_._2)
    def split[X, Y](left: Rxn[B, X], right: Rxn[C, Y]): Rxn[A, (X, Y)] =
      self >>> (left × right)
  }
}
