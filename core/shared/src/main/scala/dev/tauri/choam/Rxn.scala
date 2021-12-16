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

import java.util.{ Arrays, UUID }

import cats.{ Monad, Functor, Applicative, MonoidK, Monoid, Semigroup, Defer, Show, Align }
import cats.arrow.ArrowChoice
import cats.data.Ior
import cats.mtl.Local
import cats.effect.kernel.Unique
import cats.effect.std.UUIDGen

import mcas.{ MemoryLocation, MCAS, HalfEMCASDescriptor }

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
   * A partial implementation of reagents, described in [Reagents: Expressing and
   * Composing Fine-grained Concurrency](http://www.ccis.northeastern.edu/home/turon/reagents.pdf)
   * by Aaron Turon; originally implemented at [aturon/ChemistrySet](
   * https://github.com/aturon/ChemistrySet).
   *
   * This implementation is significantly simplified by the fact
   * that offers and permanent failure are not implemented. As a
   * consequence, these reactants are always non-blocking (provided
   * that the underlying k-CAS implementation is non-blocking).
   * However, this also means, that they are less featureful.
   *
   * On the other hand, this implementation uses an optimized and
   * stack-safe interpreter (see `interpreter`).
   *
   * Other implementations:
   * - https://github.com/aturon/Caper (Racket)
   * - https://github.com/ocamllabs/reagents (OCaml)
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
  private[choam] def tag: Byte

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
    this.map(Some(_)) + ret[A, Option[B]](None)

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
    (this * that).map(_._2)

  final def first[C]: Rxn[(A, C), (B, C)] =
    this × identity[C]

  final def second[C]: Rxn[(C, A), (C, B)] =
    identity[C] × this

  // TODO: optimize
  final def flatMap[X <: A, C](f: B => Rxn[X, C]): Rxn[X, C] = {
    val self: Rxn[X, (X, B)] = this.second[X].contramap[X](x => (x, x))
    val comp: Rxn[(X, B), C] = computed[(X, B), C](xb => f(xb._2).provide(xb._1))
    self >>> comp
  }

  // TODO: optimize
  final def flatMapF[C](f: B => Axn[C]): Rxn[A, C] =
    this >>> computed(f)

  // TODO: optimize
  final def >> [X <: A, C](that: => Rxn[X, C]): Rxn[X, C] =
    this.flatMap { _ => that }

  // TODO: reconsider this
  final def flatTap(rxn: Rxn[B, Unit]): Rxn[A, B] =
    this.flatMap { b => rxn.provide(b).as(b) }

  final def flatten[C](implicit ev: B <:< Axn[C]): Rxn[A, C] =
    this.flatMap(ev)

  final def postCommit(pc: Rxn[B, Unit]): Rxn[A, B] =
    this >>> Rxn.postCommit[B](pc)

  final def unsafePerform(
    a: A,
    kcas: MCAS,
    maxBackoff: Int = 16,
    randomizeBackoff: Boolean = true,
  ): B = {
    unsafePerformInternal(
      a = a,
      ctx = kcas.currentContext(),
      maxBackoff = maxBackoff,
      randomizeBackoff = randomizeBackoff,
    )
  }

  private[choam] final def unsafePerformInternal(
    a: A,
    ctx: MCAS.ThreadContext,
    maxBackoff: Int = 16,
    randomizeBackoff: Boolean = true,
  ): B = {
    Rxn.interpreter(
      this,
      a,
      ctx = ctx,
      maxBackoff = maxBackoff,
      randomizeBackoff = randomizeBackoff
    )
  }

  override def toString: String
}

object Rxn extends RxnInstances0 {

  private final val interruptCheckPeriod =
    16384

  // API:

  def pure[A](a: A): Axn[A] =
    ret(a)

  def ret[X, A](a: A): Rxn[X, A] =
    lift[X, A](_ => a)

  def identity[A]: Rxn[A, A] =
    lift(a => a)

  def lift[A, B](f: A => B): Rxn[A, B] =
    new Lift(f)

  def unit[A]: Rxn[A, Unit] =
    lift(_ => ()) // TODO: optimize

  def computed[A, B](f: A => Axn[B]): Rxn[A, B] =
    new Computed(f)

  final def postCommit[A](pc: Rxn[A, Unit]): Rxn[A, A] =
    new PostCommit[A](pc)

  final def unique: Axn[Unique.Token] =
    unsafe.delay { _ => new Unique.Token() }

  // Utilities:

  def consistentRead[A, B](ra: Ref[A], rb: Ref[B]): Axn[(A, B)] = {
    ra.updWith[Any, (A, B)] { (a, _) =>
      rb.upd[Any, B] { (b, _) =>
        (b, b)
      }.map { b => (a, (a, b)) }
    }
  }

  @deprecated("old implementation with invisibleRead/cas", since = "2021-03-27")
  private[choam] def consistentReadOld[A, B](ra: Ref[A], rb: Ref[B]): Axn[(A, B)] = {
    ra.unsafeInvisibleRead >>> computed[A, (A, B)] { a =>
      rb.unsafeInvisibleRead >>> computed[B, (A, B)] { b =>
        (ra.unsafeCas(a, a) × rb.unsafeCas(b, b)).provide(((), ())).map { _ => (a, b) }
      }
    }
  }

  @deprecated("old implementation with old updWith", since = "2021-11-27")
  def consistentReadWithOldUpdWith[A, B](ra: Ref[A], rb: Ref[B]): Axn[(A, B)] = {
    Rxn.ref.updWithOld[A, Any, (A, B)](ra) { (a, _) =>
      rb.upd[Any, B] { (b, _) =>
        (b, b)
      }.map { b => (a, (a, b)) }
    }
  }

  def consistentReadMany[A](refs: List[Ref[A]]): Axn[List[A]] = {
    refs match {
      case h :: t =>
        h.updWith[Any, List[A]] { (a, _) =>
          consistentReadMany(t).map { as => (a, a :: as) }
        }
      case Nil =>
        ret(Nil)
    }
  }

  def swap[A](r1: Ref[A], r2: Ref[A]): Axn[Unit] = {
    r1.updWith[Any, Unit] { (o1, _) =>
      r2.upd[Any, A] { (o2, _) =>
        (o1, o2)
      }.map { o2 => (o2, ()) }
    }
  }

  final object ref {

    def read[A](r: Ref[A]): Axn[A] =
      upd[A, Any, A](r) { (oa, _) => (oa, oa) }

    def upd[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): Rxn[B, C] =
      new Upd(r.loc, f)

    /** Old (slower) impl of `upd`, keep it for benchmarks */
    private[choam] def updDerived[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): Rxn[B, C] = {
      val self: Rxn[B, (A, B)] = r.unsafeInvisibleRead.first[B].contramap[B](b => ((), b))
      val comp: Rxn[(A, B), C] = computed[(A, B), C] { case (oa, b) =>
        val (na, c) = f(oa, b)
        r.unsafeCas(oa, na).as(c)
      }
      self >>> comp
    }

    def update[A](r: Ref[A])(f: A => A): Axn[Unit] =
      upd[A, Any, Unit](r) { (oa, _) => (f(oa), ()) }

    def updateWith[A](r: Ref[A])(f: A => Axn[A]): Axn[Unit] =
      updWith[A, Any, Unit](r) { (oa, _) => f(oa).map(na => (na, ())) }

    def getAndUpdate[A](r: Ref[A])(f: A => A): Axn[A] =
      upd[A, Any, A](r) { (oa, _) => (f(oa), oa) }

    def getAndSet[A](r: Ref[A]): Rxn[A, A] =
      upd[A, A, A](r) { (oa, na) => (na, oa) }

    def updWith[A, B, C](r: Ref[A])(f: (A, B) => Axn[(A, C)]): Rxn[B, C] =
      new UpdWith[A, B, C](r.loc, f)

    // old, derived implementation:
    private[choam] def updWithOld[A, B, C](r: Ref[A])(f: (A, B) => Axn[(A, C)]): Rxn[B, C] = {
      val self: Rxn[B, (A, B)] = Rxn.unsafe.invisibleRead(r).first[B].contramap[B](b => ((), b))
      val comp: Rxn[(A, B), C] = computed[(A, B), C] { case (oa, b) =>
        f(oa, b).flatMap {
          case (na, c) =>
            Rxn.unsafe.cas(r, oa, na).map(_ => c)
        }
      }
      self >>> comp
    }
  }

  final object unsafe {

    def invisibleRead[A](r: Ref[A]): Axn[A] =
      new InvisibleRead[A](r.loc)

    def cas[A](r: Ref[A], ov: A, nv: A): Axn[Unit] =
      new Cas[A](r.loc, ov, nv)

    def retry[A, B]: Rxn[A, B] =
      new AlwaysRetry[A, B]

    private[choam] def delay[A, B](uf: A => B): Rxn[A, B] =
      lift(uf)

    // TODO: does this make sense? is it faster than `ThreadLocalRandom.current()`?
    private[choam] def context[A](uf: MCAS.ThreadContext => A): Axn[A] =
      new Ctx[A](uf)

    // TODO: idea:
    def immediately[A, B](@unused invisibleRxn: Rxn[A, B]): Rxn[A, B] =
      sys.error("TODO: not implemented yet")

    // TODO: we need a better name
    // TODO: when we have `immediately`, this could be:
    // TODO: `immediately(prepare).flatten` (but benchmark!)
    def delayComputed[A, B](prepare: Rxn[A, Axn[B]]): Rxn[A, B] =
      new DelayComputed[A, B](prepare)

    def exchanger[A, B]: Axn[Exchanger[A, B]] =
      Exchanger.apply[A, B]

    def exchange[A, B](ex: Exchanger[A, B]): Rxn[A, B] =
      new Exchange[A, B](ex)
  }

  // Representation:

  /** Only the interpreter can use this! */
  private final class Commit[A]() extends Rxn[A, A] {
    private[choam] final def tag = 0
    final override def toString: String = "Commit()"
  }

  private final class AlwaysRetry[A, B]() extends Rxn[A, B] {
    private[choam] final def tag = 1
    final override def toString: String = "AlwaysRetry()"
  }

  private final class PostCommit[A](val pc: Rxn[A, Unit]) extends Rxn[A, A] {
    private[choam] final def tag = 2
    final override def toString: String = s"PostCommit(${pc})"
  }

  private final class Lift[A, B](val func: A => B) extends Rxn[A, B] {
    private[choam] final def tag = 3
    final override def toString: String = "Lift(<function>)"
  }

  private final class Computed[A, B](val f: A => Axn[B]) extends Rxn[A, B] {
    private[choam] final def tag = 4
    final override def toString: String = "Computed(<function>)"
  }

  // TODO: we need a better name
  private final class DelayComputed[A, B](val prepare: Rxn[A, Axn[B]]) extends Rxn[A, B] {
    private[choam] final def tag = 5
    final override def toString: String = s"DelayComputed(${prepare})"
  }

  private final class Choice[A, B](val left: Rxn[A, B], val right: Rxn[A, B]) extends Rxn[A, B] {
    private[choam] final def tag = 6
    final override def toString: String = s"Choice(${left}, ${right})"
  }

  private final class Cas[A](val ref: MemoryLocation[A], val ov: A, val nv: A) extends Rxn[Any, Unit] {
    private[choam] final def tag = 7
    final override def toString: String = s"Cas(${ref}, ${ov}, ${nv})"
  }

  private final class Upd[A, B, X](val ref: MemoryLocation[X], val f: (X, A) => (X, B)) extends Rxn[A, B] {
    private[choam] final def tag = 8
    final override def toString: String = s"Upd(${ref}, <function>)"
  }

  private final class InvisibleRead[A](val ref: MemoryLocation[A]) extends Rxn[Any, A] {
    private[choam] final def tag = 9
    final override def toString: String = s"InvisibleRead(${ref})"
  }

  private final class Exchange[A, B](val exchanger: Exchanger[A, B]) extends Rxn[A, B] {
    private[choam] final def tag = 10
    final override def toString: String = s"Exchange(${exchanger})"
  }

  private final class AndThen[A, B, C](val left: Rxn[A, B], val right: Rxn[B, C]) extends Rxn[A, C] {
    private[choam] final def tag = 11
    final override def toString: String = s"AndThen(${left}, ${right})"
  }

  private final class AndAlso[A, B, C, D](val left: Rxn[A, B], val right: Rxn[C, D]) extends Rxn[(A, C), (B, D)] {
    private[choam] final def tag = 12
    final override def toString: String = s"AndAlso(${left}, ${right})"
  }

  /** Only the interpreter can use this! */
  private final class Done[A](val result: A) extends Rxn[Any, A] {
    private[choam] final def tag = 13
    final override def toString: String = s"Done(${result})"
  }

  private final class Ctx[A](val uf: MCAS.ThreadContext => A) extends Rxn[Any, A] {
    private[choam] final def tag = 14
    final override def toString: String = s"Ctx(<block>)"
  }

  private final class Provide[A, B](val rxn: Rxn[A, B], val a: A) extends Rxn[Any, B] {
    private[choam] final override def tag = 15
    final override def toString: String = s"Provide(${rxn}, ${a})"
  }

  private final class UpdWith[A, B, C](val ref: MemoryLocation[A], val f: (A, B) => Axn[(A, C)]) extends Rxn[B, C] {
    private[choam] final override def tag = 16
    final override def toString: String = s"UpdWith(${ref}, <function>)"
  }

  private final class As[A, B, C](val rxn: Rxn[A, B], val c: C) extends Rxn[A, C] {
    private[choam] final override def tag = 17
    final override def toString: String = s"As(${rxn}, ${c})"
  }

  // Interpreter:

  private[this] def newStack[A]() = {
    new ObjStack[A]
  }

  private[choam] final val ContAndThen = 0.toByte
  private[this] final val ContAndAlso = 1.toByte
  private[this] final val ContAndAlsoJoin = 2.toByte
  private[this] final val ContAfterDelayComp = 3.toByte
  private[this] final val ContPostCommit = 4.toByte
  private[this] final val ContAfterPostCommit = 5.toByte // TODO: rename(?)
  private[this] final val ContCommitPostCommit = 6.toByte
  private[this] final val ContUpdWith = 7.toByte
  private[this] final val ContAs = 8.toByte
  private[choam] final val ContExchangerSep = 9.toByte

  private[this] final class PostCommitResultMarker // TODO: make this a java enum?
  private[this] val postCommitResultMarker =
    new PostCommitResultMarker

  private[this] final class ExchangerSeparator // TODO: make this a java enum?
  private[choam] val exchangerSeparator: Any =
    new ExchangerSeparator

  private[this] val commitSingleton: Rxn[Any, Any] =
    new Commit[Any]

  private[choam] def interpreter[X, R](
    rxn: Rxn[X, R],
    x: X,
    ctx: MCAS.ThreadContext,
    maxBackoff: Int = 16,
    randomizeBackoff: Boolean = true
  ): R = {
    /*
     * The default value of 16 for `maxBackoff` ensures that
     * there is at most 16 (or 32 with randomization) calls
     * to `onSpinWait` (see `Backoff`). Since `onSpinWait`
     * is implemented with an x86 PAUSE instruction, which
     * can use as much as 140 cycles (https://stackoverflow.com/a/44916975),
     * this means 2240 (or 4480) cycles. That seems a sensible
     * maximum (it's unlikely we'd ever want to spin for longer
     * than that without retrying).
     *
     * `randomizeBackoff` is true by default, since it seems
     * to have a small performance advantage for certain
     * operations (and no downside for others). See `SpinBench`.
     */

     new InterpreterState[X, R](
       rxn = rxn,
       x = x,
       ctx = ctx,
       maxBackoff = maxBackoff,
       randomizeBackoff = randomizeBackoff
     ).interpret()
  }

  private final class InterpreterState[X, R](
    rxn: Rxn[X, R],
    x: X,
    ctx: MCAS.ThreadContext,
    maxBackoff: Int,
    randomizeBackoff: Boolean
  ) {

    //private[this] val kcas = ctx.impl

    private[this] var delayCompStorage: ObjStack[Any] = null

    private[this] var startRxn: Rxn[Any, Any] = rxn.asInstanceOf[Rxn[Any, Any]]
    private[this] var startA: Any = x

    private[this] var desc: HalfEMCASDescriptor = ctx.start()

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

    private[this] var a: Any = x
    private[this] var retries: Int = 0

    private[this] final def setContReset(): Unit = {
      contTReset = contT.takeSnapshot()
      contKReset = contK.takeSnapshot()
    }

    private[this] final def resetConts(): Unit = {
      contT.loadSnapshot(contTReset)
      contK.loadSnapshot(contKReset)
    }

    private[this] final def saveEverything(): Unit = {
      if (delayCompStorage eq null) {
        delayCompStorage = newStack()
      }
      // save everything:
      saveAlt(null)
      delayCompStorage.push(alts.takeSnapshot())
      delayCompStorage.push(retries)
      delayCompStorage.push(startRxn)
      delayCompStorage.push(startA)
      delayCompStorage.push(Arrays.copyOf(contTReset, contTReset.length))
      delayCompStorage.push(contKReset)
      // reset state:
      desc = ctx.start()
      clearAlts()
      contT.clear()
      contK.clear()
      a = () : Any
      startA = () : Any
      retries = 0
    }

    private[this] final def clearAlts(): Unit = {
      alts.clear()
    }

    private[this] final def loadEverything(): Unit = {
      contKReset = delayCompStorage.pop().asInstanceOf[ObjStack.Lst[Any]]
      contTReset = delayCompStorage.pop().asInstanceOf[Array[Byte]]
      startA = delayCompStorage.pop()
      startRxn = delayCompStorage.pop().asInstanceOf[Rxn[Any, R]]
      retries = delayCompStorage.pop().asInstanceOf[Int]
      alts.loadSnapshot(delayCompStorage.pop().asInstanceOf[ObjStack.Lst[Any]])
      loadAlt()
      ()
    }

    private[this] final def saveAlt(k: Rxn[Any, R]): Unit = {
      alts.push(ctx.snapshot(desc))
      alts.push(a)
      alts.push(contT.takeSnapshot())
      alts.push(contK.takeSnapshot())
      alts.push(pc.takeSnapshot())
      alts.push(k)
    }

    private[this] final def loadAlt(): Rxn[Any, R] = {
      val res = alts.pop().asInstanceOf[Rxn[Any, R]]
      pc.loadSnapshotUnsafe(alts.pop().asInstanceOf[ObjStack.Lst[Any]])
      contK.loadSnapshot(alts.pop().asInstanceOf[ObjStack.Lst[Any]])
      contT.loadSnapshot(alts.pop().asInstanceOf[Array[Byte]])
      a = alts.pop()
      desc = alts.pop().asInstanceOf[HalfEMCASDescriptor]
      res
    }

    private[this] final def popFinalResult(): Any = {
      val r = contK.pop()
      assert(!equ(r, postCommitResultMarker))
      r
    }

    @tailrec
    private[this] final def next(): Rxn[Any, Any] = {
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
        case 3 => // ContAfterDelayComp
          val delayCompResult = popFinalResult().asInstanceOf[Rxn[Any, Any]]
          // continue with the rest:
          loadEverything()
          delayCompResult
        case 4 => // ContPostCommit
          val pcAction = contK.pop().asInstanceOf[Rxn[Any, Any]]
          clearAlts()
          setContReset()
          a = () : Any
          startA = () : Any
          startRxn = pcAction
          retries = 0
          desc = ctx.start()
          pcAction
        case 5 => // ContAfterPostCommit
          val res = popFinalResult()
          assert(contK.isEmpty)
          assert(contT.isEmpty)
          new Done(res)
        case 6 => // ContCommitPostCommit
          a = postCommitResultMarker : Any
          commit.asInstanceOf[Rxn[Any, Any]]
        case 7 => // ContUpdWith
          val ox = contK.pop()
          val ref = contK.pop().asInstanceOf[Ref[Any]]
          val (nx, res) = a.asInstanceOf[Tuple2[_, _]]
          desc = ctx.addCas(desc, ref.loc, ox, nx)
          a = res
          next()
        case 8 => // ContAs
          a = contK.pop()
          next()
        case 9 => // ContExchangerSep
          sys.error("TODO: ContExchangerSep in contT")
        case ct => // mustn't happen
          throw new UnsupportedOperationException(
            s"Unknown contT: ${ct}"
          )
      }
    }

    private[this] final def retry(): Rxn[Any, Any] = {
      retries += 1
      maybeCheckInterrupt()
      if (alts.nonEmpty) {
        loadAlt()
      } else {
        // really restart:
        desc = ctx.start()
        a = startA
        resetConts()
        pc.clear()
        spin()
        startRxn
      }
    }

    private[this] final def spin(): Unit = {
      if (randomizeBackoff) Backoff.backoffRandom(retries, maxBackoff)
      else Backoff.backoffConst(retries, maxBackoff)
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
    private[this] def maybeCheckInterrupt(): Unit = {
      if ((retries % Rxn.interruptCheckPeriod) == 0) {
        checkInterrupt()
      }
    }

    private[this] def checkInterrupt(): Unit = {
      if (Thread.interrupted()) {
        throw new InterruptedException
      }
    }

    @tailrec
    private[this] final def loop[A, B](curr: Rxn[A, B]): R = {
      (curr.tag : @switch) match {
        case 0 => // Commit
          if (ctx.tryPerform(desc)) {
            // save retry statistics:
            ctx.recordCommit(retries)
            // ok, commit is done, but we still need to perform post-commit actions
            val res = a
            desc = ctx.start()
            a = () : Any
            if (!equ(res, postCommitResultMarker)) {
              // final result, Done (or ContAfterDelayComp) will need it:
              contK.push(res)
            }
            while (pc.nonEmpty) {
              // commits the post-commit action:
              contT.push(ContCommitPostCommit)
              // the post-commit action itself:
              contK.push(pc.pop())
              contT.push(ContPostCommit)
              // TODO: write a test for the order of multiple post-commit results
            }
            loop(next())
          } else {
            contK.push(commit)
            contT.push(ContAndThen)
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
        case 5 => // DelayComputed
          // Note: we'll be performing `prepare` here directly;
          // as a consequence of this, `prepare` will not
          // be part of the atomic reaction, but it will run here
          // as a side-effect.
          val c = curr.asInstanceOf[DelayComputed[A, B]]
          val input = a
          saveEverything()
          contT.push(ContAfterDelayComp)
          contT.push(ContAndThen) // commit `prepare`
          contK.push(commit)
          setContReset()
          a = input
          startA = input
          startRxn = c.prepare.asInstanceOf[Rxn[Any, Any]]
          loop(c.prepare)
        case 6 => // Choice
          val c = curr.asInstanceOf[Choice[A, B]]
          saveAlt(c.right.asInstanceOf[Rxn[Any, R]])
          loop(c.left)
        case 7 => // Cas
          val c = curr.asInstanceOf[Cas[Any]]
          val currVal = ctx.read(c.ref)
          if (equ(currVal, c.ov)) {
            desc = ctx.addCas(desc, c.ref, c.ov, c.nv)
            a = () : Unit
            loop(next())
          } else {
            loop(retry())
          }
        case 8 => // Upd
          val c = curr.asInstanceOf[Upd[A, B, Any]]
          val ox = ctx.read(c.ref)
          val (nx, b) = c.f(ox, a.asInstanceOf[A])
          desc = ctx.addCas(desc, c.ref, ox, nx)
          a = b
          loop(next())
        case 9 => // InvisibleRead
          val c = curr.asInstanceOf[InvisibleRead[B]]
          a = ctx.read(c.ref)
          loop(next())
        case 10 => // Exchange
          val c = curr.asInstanceOf[Exchange[A, B]]
          c.##
          sys.error("TODO") // TODO
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
          val ox = ctx.read(c.ref)
          val axn = c.f(ox, a)
          contT.push(ContUpdWith)
          contK.push(c.ref)
          contK.push(ox)
          loop(axn)
        case 17 => // As
          val c = curr.asInstanceOf[As[_, _, _]]
          contT.push(ContAs)
          contK.push(c.c)
          loop(c.rxn)
        case t => // mustn't happen
          throw new UnsupportedOperationException(
            s"Unknown tag ${t} for ${curr}"
          )
      }
    }

    final def interpret(): R = {
      loop(startRxn)
    }
  }

// TODO: Exchanger:

/*
  private sealed abstract class GenExchange[A, B, C, D, E](
    val exchanger: Exchanger[A, B],
    val k: Rxn[D, E]
  ) extends Rxn[C, E] { self =>

    protected final override def tryPerform(n: Int, c: C, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[E] = {
      this.tryExchange(c, rd, desc, ctx) match {
        case Right(contMsg) =>
          // println(s"exchange happened, got ${contMsg} - thread#${Thread.currentThread().getId()}")
          // TODO: this way we lose exchanger statistics if we start a new reaction
          maybeJump(n, (), contMsg.cont, contMsg.rd, contMsg.desc, ctx)
        case Left(_) =>
          // TODO: pass back these stats to the main loop
          Retry
      }
    }

    private[Rxn] def tryExchange(c: C, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): Either[Exchanger.StatMap, Exchanger.Msg[Unit, Unit, E]] = {
      val msg = Exchanger.Msg[A, B, E](
        value = transform1(c),
        cont = k.lmap[B](b => self.transform2(b, c)),
        rd = rd,
        desc = desc // TODO: not threadsafe
      )
      // TODO: An `Exchange(...) + Exchange(...)` should post the
      // TODO: same offer to both exchangers, so that fulfillers
      // TODO: can race there.
      this.exchanger.tryExchange(msg, ctx)
    }

    // ...

        case 10 => // GenExchange
          val c = curr.asInstanceOf[GenExchange[ForSome.x, ForSome.y, A, ForSome.z, R]]
          val rd = ReactionData(
            postCommit = postCommit.toArray().toList,
            exchangerData = stats
          )
          c.tryExchange(a, rd, desc, ctx) match {
            case Left(newStats) =>
              stats = newStats
              if (altA.isEmpty) {
                reset()
                loop(rxn, x, retries + 1, spin = true)
              } else {
                desc = popPcAndSnap()
                loop(altK.pop(), altA.pop(), retries + 1, spin = false)
              }
            case Right(contMsg) =>
              desc = contMsg.desc
              postCommit.clear()
              postCommit.pushAll(contMsg.rd.postCommit)
              loop(contMsg.cont, (), retries, spin = false)
          }
*/
}

private[choam] sealed abstract class RxnInstances0 extends RxnInstances1 { this: Rxn.type =>

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
        case Left(a) => (ret(a) >>> f).map(Left(_))
        case Right(b) => (ret(b) >>> g).map(Right(_))
      }
    }

    final override def id[A]: Rxn[A, A] =
      identity[A]

    final override def choice[A, B, C](f: Rxn[A, C], g: Rxn[B, C]): Rxn[Either[A, B], C] = {
      computed[Either[A, B], C] {
        case Left(a) => ret(a) >>> f
        case Right(b) => ret(b) >>> g
      }
    }

    final override def lmap[A, B, X](fa: Rxn[A, B])(f: X => A): Rxn[X, B] =
      fa.contramap(f)

    final override def rmap[A, B, C](fa: Rxn[A, B])(f: B => C): Rxn[A, C] =
      fa.map(f)
  }
}

private[choam] sealed abstract class RxnInstances1 extends RxnInstances2 { self: Rxn.type =>

  implicit final def localInstance[E]: Local[Rxn[E, *], E] = new Local[Rxn[E, *], E] {
    final override def applicative: Applicative[Rxn[E, *]] =
      self.monadInstance[E]
    final override def ask[E2 >: E]: Rxn[E, E2] =
      Rxn.identity[E]
    final override def local[A](fa: Rxn[E, A])(f: E => E): Rxn[E, A] =
      fa.contramap(f)
  }
}

private[choam] sealed abstract class RxnInstances2 extends RxnInstances3 { this: Rxn.type =>

  implicit final def monadInstance[X]: Monad[Rxn[X, *]] = new Monad[Rxn[X, *]] {
    final override def flatMap[A, B](fa: Rxn[X, A])(f: A => Rxn[X, B]): Rxn[X, B] =
      fa.flatMap(f)
    final override def pure[A](a: A): Rxn[X, A] =
      Rxn.pure(a)
    final override def tailRecM[A, B](a: A)(f: A => Rxn[X, Either[A, B]]): Rxn[X, B] = {
      f(a).flatMap {
        case Left(a) => this.tailRecM(a)(f)
        case Right(b) => this.pure(b)
      }
    }
  }
}

private[choam] sealed abstract class RxnInstances3 extends RxnInstances4 { self: Rxn.type =>

  implicit final def uniqueInstance[X]: Unique[Rxn[X, *]] = new Unique[Rxn[X, *]] {
    final override def applicative: Applicative[Rxn[X, *]] =
      self.monadInstance[X]
    final override def unique: Rxn[X, Unique.Token] =
      self.unique
  }
}

private[choam] sealed abstract class RxnInstances4 extends RxnInstances5 { this: Rxn.type =>
  implicit final def monoidKInstance: MonoidK[λ[a => Rxn[a, a]]] = {
    new MonoidK[λ[a => Rxn[a, a]]] {
      final override def combineK[A](x: Rxn[A, A], y: Rxn[A, A]): Rxn[A, A] =
        x >>> y
      final override def empty[A]: Rxn[A, A] =
        Rxn.identity[A]
    }
  }
}

private[choam] sealed abstract class RxnInstances5 extends RxnInstances6 { this: Rxn.type =>

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
      Rxn.ret(B.empty)
  }
}

private[choam] sealed abstract class RxnInstances6 extends RxnInstances7 { self: Rxn.type =>
  implicit final def deferInstance[X]: Defer[Rxn[X, *]] = new Defer[Rxn[X, *]] {
    final override def defer[A](fa: => Rxn[X, A]): Rxn[X, A] =
      self.computed[X, A] { x => fa.provide(x) }
  }
}

private[choam] sealed abstract class RxnInstances7 extends RxnInstances8 { self: Rxn.type =>
  implicit final def showInstance[A, B]: Show[Rxn[A, B]] =
    Show.fromToString
}

private[choam] sealed abstract class RxnInstances8 extends RxnInstances9 { self: Rxn.type =>
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

private[choam] sealed abstract class RxnInstances9 extends RxnSyntax0 { self: Rxn.type =>
  implicit final def uuidGenInstance[X]: UUIDGen[Rxn[X, *]] = new UUIDGen[Rxn[X, *]] {
    final override def randomUUID: Rxn[X, UUID] =
      Rxn.unsafe.delay { _ => UUID.randomUUID() }
  }
}

private[choam] sealed abstract class RxnSyntax0 extends RxnSyntax1 { this: Rxn.type =>
  implicit final class InvariantSyntax[A, B](private val self: Rxn[A, B]) {
    final def apply[F[_]](a: A)(implicit F: Reactive[F]): F[B] =
      F.run(self, a)
  }
}

private[choam] sealed abstract class RxnSyntax1 extends RxnSyntax2 { this: Rxn.type =>

  implicit final class UnitSyntax[A](private val self: Rxn[Unit, A]) {

    final def run[F[_]](implicit F: Reactive[F]): F[A] =
      F.run(self, ())

    final def unsafeRun(
      kcas: MCAS,
      maxBackoff: Int = 16,
      randomizeBackoff: Boolean = true
    ): A = {
      self.unsafePerform((), kcas, maxBackoff = maxBackoff, randomizeBackoff = randomizeBackoff)
    }
  }
}

private[choam] sealed abstract class RxnSyntax2 { this: Rxn.type =>

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
