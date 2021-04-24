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

import scala.annotation.switch

// To avoid going through the aliases in scala._:
import scala.collection.immutable.{ Nil, :: }

import cats.{ Applicative, Monad }
import cats.arrow.ArrowChoice
import cats.mtl.Local

import kcas._

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

  import Rxn._

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
   * Other implementations:
   * - https://github.com/aturon/Caper (Racket)
   * - https://github.com/ocamllabs/reagents (OCaml)
   */

  final def unsafePerform(a: A, kcas: KCAS, maxBackoff: Int = 16, randomizeBackoff: Boolean = true): B = {

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

    val ctx = kcas.currentContext()
    ctx.maxBackoff = maxBackoff
    ctx.randomizeBackoff = randomizeBackoff

    @tailrec
    def go[C](partialResult: C, cont: Rxn[C, B], rd: ReactionData, desc: EMCASDescriptor, alts: List[SnapJump[_, B]], retries: Int): Success[B] = {
      cont.tryPerform(maxStackDepth, partialResult, rd, desc, ctx) match {
        case Retry =>
          // TODO: don't back off if there are `alts`
          if (randomizeBackoff) Backoff.backoffRandom(retries, maxBackoff)
          else Backoff.backoffConst(retries, maxBackoff)
          doOnRetry()
          alts match {
            case _: Nil.type =>
              go(partialResult, cont, ReactionData(Nil, rd.exchangerData), kcas.start(ctx), alts, retries = retries + 1)
            case (h: SnapJump[x, B]) :: t =>
              go[x](h.value, h.rxn, h.rd, h.snap, t, retries = retries + 1)
          }
        case s @ Success(_, _) =>
          resetOnRetry()
          s
        case Jump(pr, k, rea, desc, alts2) =>
          go(pr, k, rea, desc, alts2 ++ alts, retries = retries)
          // TODO: optimize concat
      }
    }

    def doOnRetry(): Unit = {
      val it = ctx.onRetry.iterator()
      while (it.hasNext) {
        it.next().unsafePerform((), kcas, maxBackoff = maxBackoff, randomizeBackoff = randomizeBackoff)
      }
      resetOnRetry()
    }

    def resetOnRetry(): Unit = {
      ctx.onRetry = new java.util.ArrayList
    }

    val res = go(a, this, ReactionData(Nil, Map.empty), kcas.start(ctx), Nil, retries = 0)
    res.reactionData.postCommit.foreach { pc =>
      pc.unsafePerform((), kcas, maxBackoff = maxBackoff, randomizeBackoff = randomizeBackoff)
    }

    res.value
  }

  /**
   * Tag for the external interpreter (see `externalInterpreter`)
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

  protected def tryPerform(n: Int, a: A, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[B]

  protected final def maybeJump[C, Y >: B](
    n: Int,
    partialResult: C,
    cont: Rxn[C, Y],
    ops: ReactionData,
    desc: EMCASDescriptor,
    ctx: ThreadContext
  ): TentativeResult[Y] = {
    if (n <= 0) Jump(partialResult, cont, ops, desc, Nil)
    else cont.tryPerform(n - 1, partialResult, ops, desc, ctx)
  }

  final def + [X <: A, Y >: B](that: Rxn[X, Y]): Rxn[X, Y] =
    new Choice[X, Y](this, that)

  final def >>> [C](that: Rxn[B, C]): Rxn[A, C] = that match {
    case _: Commit[_] => this
    case _ => this.andThenImpl(that)
  }

  protected def andThenImpl[C](that: Rxn[B, C]): Rxn[A, C]

  final def * [X <: A, C](that: Rxn[X, C]): Rxn[X, (B, C)] = {
    (this × that).lmap[X](x => (x, x))
  }

  final def × [C, D](that: Rxn[C, D]): Rxn[(A, C), (B, D)] =
    this.productImpl(that)

  final def ? : Rxn[A, Option[B]] =
    this.rmap(Some(_)) + ret[A, Option[B]](None)

  final def dup: Rxn[A, (B, B)] =
    this.map { b => (b, b) }

  protected def productImpl[C, D](that: Rxn[C, D]): Rxn[(A, C), (B, D)]

  protected[choam] def firstImpl[C]: Rxn[(A, C), (B, C)]

  final def lmap[C](f: C => A): Rxn[C, B] =
    lift(f) >>> this

  final def rmap[C](f: B => C): Rxn[A, C] =
    this >>> lift(f)

  final def map[C](f: B => C): Rxn[A, C] =
    rmap(f)

  final def as[C](c: C): Rxn[A, C] =
    map(_ => c) // TODO: optimize

  final def provide(a: A): Axn[B] =
    contramap[Any](_ => a) // TODO: optimize

  final def contramap[C](f: C => A): Rxn[C, B] =
    lmap(f)

  final def dimap[C, D](f: C => A)(g: B => D): Rxn[C, D] =
    this.lmap(f).rmap(g)

  final def map2[X <: A, C, D](that: Rxn[X, C])(f: (B, C) => D): Rxn[X, D] =
    (this * that).map(f.tupled)

  // TODO: maybe rename to `void`
  final def discard: Rxn[A, Unit] =
    this.rmap(_ => ()) // TODO: optimize

  final def flatMap[X <: A, C](f: B => Rxn[X, C]): Rxn[X, C] = {
    val self: Rxn[X, (X, B)] = arrowChoiceInstance.second[X, B, X](this).lmap[X](x => (x, x))
    val comp: Rxn[(X, B), C] = computed[(X, B), C](xb => f(xb._2).provide(xb._1))
    self >>> comp
  }

  final def <* [X <: A, C](that: Rxn[X, C]): Rxn[X, B] =
    this.productL(that)

  final def productL [X <: A, C](that: Rxn[X, C]): Rxn[X, B] =
    (this * that).map(_._1)

  final def *> [X <: A, C](that: Rxn[X, C]): Rxn[X, C] =
    this.productR(that)

  final def productR[X <: A, C](that: Rxn[X, C]): Rxn[X, C] =
    (this * that).map(_._2)

  final def toFunction: A => Axn[B] = { (a: A) =>
    this.provide(a)
  }

  // TODO: public API?
  private[choam] final def postCommit(pc: Rxn[B, Unit]): Rxn[A, B] =
    this >>> Rxn.postCommit(pc)

  override def toString: String
}

object Rxn extends RxnSyntax0 {

  private[choam] final val maxStackDepth = 1024

  /** Old (slower) impl of `upd`, keep it for benchmarks */
  private[choam] def updDerived[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): Rxn[B, C] = {
    val self: Rxn[B, (A, B)] = r.unsafeInvisibleRead.firstImpl[B].lmap[B](b => ((), b))
    val comp: Rxn[(A, B), C] = computed[(A, B), C] { case (oa, b) =>
      val (na, c) = f(oa, b)
      r.unsafeCas(oa, na).rmap(_ => c)
    }
    self >>> comp
  }

  def upd[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): Rxn[B, C] =
    new Upd(r, f, Commit[C]())

  def updWith[A, B, C](r: Ref[A])(f: (A, B) => Axn[(A, C)]): Rxn[B, C] = {
    val self: Rxn[B, (A, B)] = r.unsafeInvisibleRead.firstImpl[B].lmap[B](b => ((), b))
    val comp: Rxn[(A, B), C] = computed[(A, B), C] { case (oa, b) =>
      f(oa, b).flatMap {
        case (na, c) =>
          r.unsafeCas(oa, na).rmap(_ => c)
      }
    }
    self >>> comp
  }

  @deprecated("old implementation with invisibleRead/cas", since = "2021-03-27")
  private[choam] def consistentReadOld[A, B](ra: Ref[A], rb: Ref[B]): Axn[(A, B)] = {
    ra.unsafeInvisibleRead >>> computed[A, (A, B)] { a =>
      rb.unsafeInvisibleRead >>> computed[B, (A, B)] { b =>
        (ra.unsafeCas(a, a) × rb.unsafeCas(b, b)).provide(((), ())).map { _ => (a, b) }
      }
    }
  }

  // TODO: generalize to more than 2
  def consistentRead[A, B](ra: Ref[A], rb: Ref[B]): Axn[(A, B)] = {
    ra.updWith[Any, (A, B)] { (a, _) =>
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

  def computed[A, B](f: A => Axn[B]): Rxn[A, B] =
    new Computed[A, B, B](f, Commit[B]())

  // TODO: why is this private?
  private[choam] def postCommit[A](pc: Rxn[A, Unit]): Rxn[A, A] =
    new PostCommit[A, A](pc, Commit[A]())

  def lift[A, B](f: A => B): Rxn[A, B] =
    new Lift(f, Commit[B]())

  private[choam] def delay[A, B](uf: A => B): Rxn[A, B] =
    lift(uf)

  def identity[A]: Rxn[A, A] =
    Commit[A]()

  private[this] val _unit =
    Rxn.lift[Any, Unit] { _ => () }

  def unit[A]: Rxn[A, Unit] =
    _unit

  def ret[X, A](a: A): Rxn[X, A] =
    lift[X, A](_ => a)

  def pure[A](a: A): Axn[A] =
    ret(a)

  final object unsafe {

    def cas[A](r: Ref[A], ov: A, nv: A): Axn[Unit] =
      new Cas[A, Unit](r, ov, nv, Rxn.unit)

    def invisibleRead[A](r: Ref[A]): Axn[A] =
      new Read(r, Commit[A]())

    def retry[A, B]: Rxn[A, B] =
      AlwaysRetry()

    // TODO: better name
    def delayComputed[A, B](prepare: Rxn[A, Axn[B]]): Rxn[A, B] =
      new DelayComputed[A, B, B](prepare, Commit[B]())

    def exchanger[A, B]: Axn[Exchanger[A, B]] =
      Exchanger.apply[A, B]

    def exchange[A, B](ex: Exchanger[A, B]): Rxn[A, B] =
      new Exchange[A, B, B](ex, Commit[B]())

    // FIXME:
    def onRetry[A, B](r: Rxn[A, B])(act: Axn[Unit]): Rxn[A, B] =
      onRetryImpl(act, r)

    private[this] def onRetryImpl[A, B](act: Axn[Unit], k: Rxn[A, B]): Rxn[A, B] = {
      new Rxn[A, B] {

        private[choam] def tag = -1 // TODO

        override protected def tryPerform(n: Int, a: A, ops: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[B] = {
          ctx.onRetry.add(act)
          maybeJump(n, a, k, ops, desc, ctx)
        }

        override protected def andThenImpl[C](that: Rxn[B, C]): Rxn[A, C] =
          onRetryImpl(act, k >>> that)

        override protected def productImpl[C, D](that: Rxn[C, D]): Rxn[(A, C), (B, D)] =
          onRetryImpl(act, k × that)

        override protected[choam] def firstImpl[C]: Rxn[(A, C), (B, C)] =
          onRetryImpl(act, k.firstImpl[C])

        override def toString(): String = s"OnRetry(${act}, ${k})"
      }
    }
  }

  // TODO: can we put this directly on `Rxn`?
  implicit final class InvariantRxnSyntax[A, B](private val self: Rxn[A, B]) extends AnyVal {
    final def apply[F[_]](a: A)(implicit F: Reactive[F]): F[B] =
      F.run(self, a)
  }

  implicit final class UnitRxnSyntax[A](private val self: Rxn[Unit, A]) extends AnyVal {

    // TODO: maybe this should be the default `flatMap`? (Not sure...)
    final def flatMapU[X, C](f: A => Rxn[X, C]): Rxn[X, C] = {
      val self2: Rxn[X, (X, A)] =
        Rxn.arrowChoiceInstance.second[Unit, A, X](self).lmap[X](x => (x, ()))
      val comp: Rxn[(X, A), C] =
        Rxn.computed[(X, A), C](xb => f(xb._2).provide(xb._1))
      self2 >>> comp
    }

    final def run[F[_]](implicit F: Reactive[F]): F[A] =
      F.run(self, ())

    final def unsafeRun(kcas: KCAS): A =
      self.unsafePerform((), kcas)

    final def void: Axn[Unit] =
      self.discard.provide(())
  }

  implicit final class Tuple2RxnSyntax[A, B, C](private val self: Rxn[A, (B, C)]) extends AnyVal {
    def left: Rxn[A, B] =
      self.rmap(_._1)
    def right: Rxn[A, C] =
      self.rmap(_._2)
    def split[X, Y](left: Rxn[B, X], right: Rxn[C, Y]): Rxn[A, (X, Y)] =
      self >>> (left × right)
  }

  private[choam] final class ReactionData private (
    val postCommit: List[Axn[Unit]],
    val exchangerData: Exchanger.StatMap
  ) {

    def withPostCommit(act: Axn[Unit]): ReactionData = {
      ReactionData(
        postCommit = act :: this.postCommit,
        exchangerData = this.exchangerData
      )
    }
  }

  private[choam] final object ReactionData {
    def apply(
      postCommit: List[Axn[Unit]],
      exchangerData: Exchanger.StatMap
    ): ReactionData = {
      new ReactionData(postCommit, exchangerData)
    }
  }

  protected[Rxn] sealed trait TentativeResult[+A]
  protected[Rxn] final case object Retry extends TentativeResult[Nothing]
  protected[Rxn] final case class Success[+A](value: A, reactionData: ReactionData) extends TentativeResult[A]
  protected[Rxn] final case class Jump[A, +B](
    value: A,
    rxn: Rxn[A, B],
    rd: ReactionData,
    desc: EMCASDescriptor,
    alts: List[SnapJump[_, B]]
  ) extends TentativeResult[B] {

    def withAlt[X, Y >: B](alt: SnapJump[X, Y]): Jump[A, Y] = {
      this.copy(alts = alt :: alts)
    }
  }

  protected[Rxn] final case class SnapJump[A, +B](
    value: A,
    rxn: Rxn[A, B],
    rd: ReactionData,
    snap: EMCASDescriptor
  ) {

    def map[C](f: B => C): SnapJump[A, C] = {
      SnapJump(value, rxn.map(f), rd, snap)
    }
  }

  private final class Commit[A]()
      extends Rxn[A, A] {

    private[choam] def tag = 0

    protected final def tryPerform(n: Int, a: A, reaction: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[A] = {
      if (ctx.impl.tryPerform(desc, ctx)) Success(a, reaction)
      else Retry
    }

    protected final def andThenImpl[C](that: Rxn[A, C]): Rxn[A, C] =
      that

    protected final def productImpl[C, D](that: Rxn[C, D]): Rxn[(A, C), (A, D)] = that match {
      case _: Commit[_] => Commit[(A, C)]()
      case _ => arrowChoiceInstance.second(that) // TODO: optimize
    }

    final override def firstImpl[C]: Rxn[(A, C), (A, C)] =
      Commit[(A, C)]()

    final override def toString =
      "Commit"
  }

  // TODO: lazy val may block
  private[this] lazy val commitInstance: Commit[Any] =
    new Commit[Any]

  @inline
  private[this] def Commit[A](): Commit[A] =
    this.commitInstance.asInstanceOf[Commit[A]]

  private sealed abstract class AlwaysRetry[A, B]()
      extends Rxn[A, B] {

    private[choam] def tag = 1

    protected final def tryPerform(n: Int, a: A, reaction: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[B] =
      Retry

    protected final def andThenImpl[C](that: Rxn[B, C]): Rxn[A, C] =
      AlwaysRetry()

    protected final def productImpl[C, D](that: Rxn[C, D]): Rxn[(A, C), (B, D)] =
      AlwaysRetry()

    final def firstImpl[C]: Rxn[(A, C), (B, C)] =
      AlwaysRetry()

    final override def toString =
      "Retry"
  }

  private final object AlwaysRetry extends AlwaysRetry[Any, Any] {

    @inline
    def apply[A, B](): AlwaysRetry[A, B] =
      this.asInstanceOf[AlwaysRetry[A, B]]
  }

  private final class PostCommit[A, B](val pc: Rxn[A, Unit], val k: Rxn[A, B])
      extends Rxn[A, B] {

    private[choam] def tag = 2

    def tryPerform(n: Int, a: A, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[B] =
      maybeJump(n, a, k, rd.withPostCommit(pc.provide(a)), desc, ctx)

    def andThenImpl[C](that: Rxn[B, C]): Rxn[A, C] =
      new PostCommit[A, C](pc, k >>> that)

    def productImpl[C, D](that: Rxn[C, D]): Rxn[(A, C), (B, D)] =
      new PostCommit[(A, C), (B, D)](pc.lmap[(A, C)](_._1), k × that)

    def firstImpl[C]: Rxn[(A, C), (B, C)] =
      new PostCommit[(A, C), (B, C)](pc.lmap[(A, C)](_._1), k.firstImpl[C])

    override def toString: String =
      s"PostCommit(${pc}, ${k})"
  }

  private final class Lift[A, B, C](val func: A => B, val k: Rxn[B, C])
      extends Rxn[A, C] {

    private[choam] def tag = 3

    def tryPerform(n: Int, a: A, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[C] =
      maybeJump(n, func(a), k, rd, desc, ctx)

    def andThenImpl[D](that: Rxn[C, D]): Rxn[A, D] = {
      new Lift[A, B, D](func, k >>> that)
    }

    def productImpl[D, E](that: Rxn[D, E]): Rxn[(A, D), (C, E)] = that match {
      case _: Commit[_] =>
        new Lift[(A, D), (B, D), (C, D)](ad => (func(ad._1), ad._2), k.firstImpl)
      case l: Lift[_, _, _] =>
        new Lift(ad => (func(ad._1), l.func(ad._2)), k × l.k)
      case _ =>
        new Lift(ad => (func(ad._1), ad._2), k × that)
    }

    def firstImpl[D]: Rxn[(A, D), (C, D)] =
      new Lift[(A, D), (B, D), (C, D)](ad => (func(ad._1), ad._2), k.firstImpl[D])

    override def toString: String =
      s"Lift(<function>, ${k})"
  }

  private final class Computed[A, B, C](val f: A => Axn[B], val k: Rxn[B, C])
      extends Rxn[A, C] {

    private[choam] def tag = 4

    def tryPerform(n: Int, a: A, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[C] =
      maybeJump(n, (), f(a) >>> k, rd, desc, ctx)

    def andThenImpl[D](that: Rxn[C, D]): Rxn[A, D] = {
      new Computed(f, k >>> that)
    }

    def productImpl[D, E](that: Rxn[D, E]): Rxn[(A, D), (C, E)] = {
      new Computed[(A, D), (B, D), (C, E)](
        ad => f(ad._1).rmap(b => (b, ad._2)),
        k × that
      )
    }

    def firstImpl[D]: Rxn[(A, D), (C, D)] = {
      new Computed[(A, D), (B, D), (C, D)](
        ad => f(ad._1).firstImpl[D].provide(((), ad._2)),
        k.firstImpl[D]
      )
    }

    override def toString: String =
      s"Computed(<function>, ${k})"
  }

  private final class DelayComputed[A, B, C](val prepare: Rxn[A, Axn[B]], val k: Rxn[B, C])
    extends Rxn[A, C] {

    private[choam] def tag = 5

    override def tryPerform(n: Int, a: A, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[C] = {
      // Note: we're performing `prepare` here directly;
      // as a consequence of this, `prepare` will not
      // be part of the atomic reaction, but it runs here
      // as a side-effect.
      val r: Axn[B] = prepare.unsafePerform(
        a,
        ctx.impl,
        maxBackoff = ctx.maxBackoff,
        randomizeBackoff = ctx.randomizeBackoff
      )
      maybeJump(n, (), r >>> k, rd, desc, ctx)
    }

    override def andThenImpl[D](that: Rxn[C, D]): Rxn[A, D] = {
      new DelayComputed(prepare, k >>> that)
    }

    override def productImpl[D, E](that: Rxn[D, E]): Rxn[(A, D), (C, E)] = {
      new DelayComputed[(A, D), (B, D), (C, E)](
        prepare.firstImpl[D].map { case (r, d) =>
          r.map { b => (b, d) }
        },
        k × that
      )
    }

    override def firstImpl[D]: Rxn[(A, D), (C, D)] = {
      new DelayComputed[(A, D), (B, D), (C, D)](
        prepare.firstImpl[D].map { case (r, d) =>
          r.map { b => (b, d) }
        },
        k.firstImpl[D]
      )
    }

    override def toString: String =
      s"DelayComputed(${prepare}, ${k})"
  }

  private final class Choice[A, B](val first: Rxn[A, B], val second: Rxn[A, B])
      extends Rxn[A, B] {

    private[choam] def tag = 6

    def tryPerform(n: Int, a: A, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[B] = {
      if (n <= 0) {
        Jump(a, this, rd, desc, Nil)
      } else {
        val snap = ctx.impl.snapshot(desc, ctx)
        first.tryPerform(n - 1, a, rd, desc, ctx) match {
          case _: Retry.type =>
            second.tryPerform(n - 1, a, rd, snap, ctx)
          case jmp: Jump[_, _] =>
            // TODO: optimize building `alts`
            (jmp : Jump[_, B]).withAlt[A, B](SnapJump[A, B](a, second, rd, snap))
          case ok =>
            ok
        }
      }
    }

    def andThenImpl[C](that: Rxn[B, C]): Rxn[A, C] =
      new Choice[A, C](first >>> that, second >>> that)

    def productImpl[C, D](that: Rxn[C, D]): Rxn[(A, C), (B, D)] =
      new Choice[(A, C), (B, D)](first × that, second × that)

    def firstImpl[C]: Rxn[(A, C), (B, C)] =
      new Choice[(A, C), (B, C)](first.firstImpl, second.firstImpl)

    override def toString: String =
      s"Choice(${first}, ${second})"
  }

  private abstract class GenCas[A, B, C, D](val ref: Ref[A], val ov: A, val nv: A, val k: Rxn[C, D])
    extends Rxn[B, D] { self =>

    private[choam] def tag = 7

    /** Must be pure */
    private[choam] def transform(a: A, b: B): C

    protected final def tryPerform(n: Int, b: B, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[D] = {
      val a = ctx.impl.read(ref, ctx)
      if (equ(a, ov)) {
        maybeJump(n, transform(a, b), k, rd, ctx.impl.addCas(desc, ref, ov, nv, ctx), ctx)
      } else {
        Retry
      }
    }

    final def andThenImpl[E](that: Rxn[D, E]): Rxn[B, E] = {
      new GenCas[A, B, C, E](ref, ov, nv, k >>> that) {
        private[choam] def transform(a: A, b: B): C =
          self.transform(a, b)
      }
    }

    final def productImpl[E, F](that: Rxn[E, F]): Rxn[(B, E), (D, F)] = {
      new GenCas[A, (B, E), (C, E), (D, F)](ref, ov, nv, k × that) {
        private[choam] def transform(a: A, be: (B, E)): (C, E) =
          (self.transform(a, be._1), be._2)
      }
    }

    final def firstImpl[E]: Rxn[(B, E), (D, E)] = {
      new GenCas[A, (B, E), (C, E), (D, E)](ref, ov, nv, k.firstImpl[E]) {
        private[choam] def transform(a: A, be: (B, E)): (C, E) =
          (self.transform(a, be._1), be._2)
      }
    }

    final override def toString: String =
      s"GenCas(${ref}, ${ov}, ${nv}, ${k})"
  }

  private final class Cas[A, B](ref: Ref[A], ov: A, nv: A, k: Rxn[A, B])
      extends GenCas[A, Any, A, B](ref, ov, nv, k) {

    def transform(a: A, b: Any): A =
      a
  }

  private final class Upd[A, B, C, X](val ref: Ref[X], val f: (X, A) => (X, B), val k: Rxn[B, C])
    extends Rxn[A, C] { self =>

    private[choam] def tag = 8

    protected final override def tryPerform(n: Int, a: A, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[C] = {
      val ox = ctx.impl.read(ref, ctx)
      val (nx, b) = f(ox, a)
      maybeJump(n, b, k, rd, ctx.impl.addCas(desc, ref, ox, nx, ctx), ctx)
    }

    protected final override def andThenImpl[D](that: Rxn[C, D]): Rxn[A, D] =
      new Upd[A, B, D, X](ref, f, k.andThenImpl(that))

    protected final override def productImpl[D, E](that: Rxn[D, E]): Rxn[(A, D), (C, E)] =
      new Upd[(A, D), (B, D), (C, E), X](ref, this.fFirst[D], k.productImpl(that))

    protected[choam] final override def firstImpl[D]: Rxn[(A, D), (C, D)] =
      new Upd[(A, D), (B, D), (C, D), X](ref, this.fFirst[D], k.firstImpl[D])

    final override def toString: String =
      s"Upd(${ref}, <function>, ${k})"

    private[this] def fFirst[D](ox: X, ad: (A, D)): (X, (B, D)) = {
      val (x, b) = this.f(ox, ad._1)
      (x, (b, ad._2))
    }
  }

  private abstract class GenRead[A, B, C, D](val ref: Ref[A], val k: Rxn[C, D])
      extends Rxn[B, D] { self =>

    private[choam] def tag = 9

    /** Must be pure */
    private[choam] def transform(a: A, b: B): C

    protected final override def tryPerform(n: Int, b: B, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[D] = {
      val a = ctx.impl.read(ref, ctx)
      maybeJump(n, transform(a, b), k, rd, desc, ctx)
    }

    final override def andThenImpl[E](that: Rxn[D, E]): Rxn[B, E] = {
      new GenRead[A, B, C, E](ref, k >>> that) {
        private[choam] def transform(a: A, b: B): C =
          self.transform(a, b)
      }
    }

    final override def productImpl[E, F](that: Rxn[E, F]): Rxn[(B, E), (D, F)] = {
      new GenRead[A, (B, E), (C, E), (D, F)](ref, k × that) {
        private[choam] def transform(a: A, be: (B, E)): (C, E) =
          (self.transform(a, be._1), be._2)
      }
    }

    final override def firstImpl[E]: Rxn[(B, E), (D, E)] = {
      new GenRead[A, (B, E), (C, E), (D, E)](ref, k.firstImpl[E]) {
        private[choam] def transform(a: A, be: (B, E)): (C, E) =
          (self.transform(a, be._1), be._2)
      }
    }

    final override def toString: String =
      s"GenRead(${ref}, ${k})"
  }

  private final class Read[A, B](ref: Ref[A], k: Rxn[A, B])
      extends GenRead[A, Any, A, B](ref, k) {

    final override def transform(a: A, b: Any): A =
      a
  }

  private sealed abstract class GenExchange[A, B, C, D, E](
    val exchanger: Exchanger[A, B],
    val k: Rxn[D, E]
  ) extends Rxn[C, E] { self =>

    private[choam] def tag = 10

    protected def transform1(c: C): A

    protected def transform2(b: B, c: C): D

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

    private[Rxn] def tryExchange(c: C, rd: ReactionData, desc:EMCASDescriptor, ctx: ThreadContext): Either[Exchanger.StatMap, Exchanger.Msg[Unit, Unit, E]] = {
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

    protected final override def andThenImpl[F](that: Rxn[E, F]): Rxn[C, F] = {
      new GenExchange[A, B, C, D, F](this.exchanger, this.k >>> that) {
        protected override def transform1(c: C): A =
          self.transform1(c)
        protected override def transform2(b: B, c: C): D =
          self.transform2(b, c)
      }
    }

    protected final override def productImpl[F, G](that: Rxn[F, G]): Rxn[(C, F), (E, G)] = {
      new GenExchange[A, B, (C, F), (D, F), (E, G)](exchanger, k.productImpl(that)) {
        protected override def transform1(cf: (C, F)): A =
          self.transform1(cf._1)
        protected override def transform2(b: B, cf: (C, F)): (D, F) = {
          val d = self.transform2(b, cf._1)
          (d, cf._2)
        }
      }
    }

    protected[choam] final override def firstImpl[F]: Rxn[(C, F), (E, F)] = {
      new GenExchange[A, B, (C, F), (D, F), (E, F)](exchanger, k.firstImpl[F]) {
        protected override def transform1(cf: (C, F)): A =
          self.transform1(cf._1)
        protected override def transform2(b: B, cf: (C, F)): (D, F) = {
          val d = self.transform2(b, cf._1)
          (d, cf._2)
        }
      }
    }

    final override def toString: String =
      s"GenExchange(${exchanger}, ${k})"
  }

  private final class Exchange[A, B, C](
    exchanger: Exchanger[A, B],
    k: Rxn[B, C]
  ) extends GenExchange[A, B, A, B, C](exchanger, k) {

    override protected def transform1(a: A): A =
      a

    override protected def transform2(b: B, a: A): B =
      b
  }

  private final object ForSome {
    type x
    type y
    type z
  }

  private[this] def newStack[A]() = {
    new ObjStack[A](initSize = 8)
  }

  private[choam] def externalInterpreter[X, R](
    rxn: Rxn[X, R],
    x: X,
    ctx: ThreadContext,
    maxBackoff: Int = 16,
    randomizeBackoff: Boolean = true
  ): R = {

    val kcas = ctx.impl

    var desc: EMCASDescriptor = kcas.start(ctx)
    var stats: Exchanger.StatMap = Exchanger.StatMap.empty

    val postCommit = newStack[Axn[Unit]]()

    val altSnap = newStack[EMCASDescriptor]()
    val altA = newStack[ForSome.x]()
    val altK = newStack[Rxn[ForSome.x, R]]()
    val altPc = newStack[Array[Rxn[Any, Unit]]]()

    def reset(): Unit = {
      desc = kcas.start(ctx)
      postCommit.clear()
    }

    def popPcAndSnap(): EMCASDescriptor = {
      postCommit.clear()
      postCommit.replaceWith(altPc.pop())
      altSnap.pop()
    }

    @tailrec
    def loop[A](curr: Rxn[A, R], a: A, retries: Int, spin: Boolean): R = {
      if (spin) {
        if (randomizeBackoff) Backoff.backoffRandom(retries, maxBackoff)
        else Backoff.backoffConst(retries, maxBackoff)
      }

      (curr.tag : @switch) match {
        case 0 => // Commit
          if (kcas.tryPerform(desc, ctx)) {
            a.asInstanceOf[R]
          } else if (altA.isEmpty) {
            reset()
            loop(rxn, x, retries + 1, spin = true)
          } else {
            desc = popPcAndSnap()
            loop(altK.pop(), altA.pop(), retries + 1, spin = false)
          }
        case 1 => // AlwaysRetry
          if (altA.isEmpty) {
            reset()
            loop(rxn, x, retries + 1, spin = true)
          } else {
            desc = popPcAndSnap()
            loop(altK.pop(), altA.pop(), retries + 1, spin = false)
          }
        case 2 => // PostCommit
          val c = curr.asInstanceOf[PostCommit[A, R]]
          postCommit.push(c.pc.provide(a))
          loop(c.k, a, retries, spin = false)
        case 3 => // Lift
          val c = curr.asInstanceOf[Lift[A, ForSome.x, R]]
          loop(c.k, c.func(a), retries, spin = false)
        case 4 => // Computed
          val c = curr.asInstanceOf[Computed[A, ForSome.x, R]]
          loop(c.f(a) >>> c.k, (), retries, spin = false)
        case 5 => // DelayComputed
          val c = curr.asInstanceOf[DelayComputed[A, ForSome.x, R]]
          // Note: we're performing `prepare` here directly;
          // as a consequence of this, `prepare` will not
          // be part of the atomic reaction, but it runs here
          // as a side-effect.
          val r: Axn[ForSome.x] = externalInterpreter(
            c.prepare,
            a,
            ctx,
            maxBackoff = maxBackoff,
            randomizeBackoff = randomizeBackoff
          )
          loop(r >>> c.k, (), retries, spin = false)
        case 6 => // Choice
          val c = curr.asInstanceOf[Choice[A, R]]
          altSnap.push(ctx.impl.snapshot(desc, ctx))
          altA.push(a.asInstanceOf[ForSome.x])
          altK.push(c.second.asInstanceOf[Rxn[ForSome.x, R]])
          altPc.push(postCommit.toArray)
          loop(c.first, a, retries, spin = false)
        case 7 => // GenCas
          val c = curr.asInstanceOf[GenCas[ForSome.x, A, ForSome.y, R]]
          val currVal = kcas.read(c.ref, ctx)
          if (equ(currVal, c.ov)) {
            desc = kcas.addCas(desc, c.ref, c.ov, c.nv, ctx)
            loop(c.k, c.transform(currVal, a), retries, spin = false)
          } else if (altA.isEmpty) {
            reset()
            loop(rxn, x, retries + 1, spin = true)
          } else {
            desc = popPcAndSnap()
            loop(altK.pop(), altA.pop(), retries + 1, spin = false)
          }
        case 8 => // Upd
          val c = curr.asInstanceOf[Upd[A, ForSome.y, R, ForSome.x]]
          val currVal = kcas.read(c.ref, ctx)
          val nextVal = c.f(currVal, a)
          desc = kcas.addCas(desc, c.ref, currVal, nextVal._1, ctx)
          loop(c.k, nextVal._2, retries, spin = false)
        case 9 => // GenRead
          val c = curr.asInstanceOf[GenRead[ForSome.x, A, ForSome.y, R]]
          val currVal = ctx.impl.read(c.ref, ctx)
          loop(c.k, c.transform(currVal, a), retries, spin = false)
        case 10 => // GenExchange
          val c = curr.asInstanceOf[GenExchange[ForSome.x, ForSome.y, A, ForSome.z, R]]
          val rd = ReactionData(
            postCommit = postCommit.toArray.toList,
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
        case t => // not implemented
          throw new UnsupportedOperationException(
            s"Not implemented tag ${t} for ${curr}"
          )
      }
    }

    val result: R = loop(rxn, x, retries = 0, spin = false)
    doPostCommit(postCommit, ctx)
    result
  }

  private[this] def doPostCommit(it: ObjStack[Axn[Unit]], ctx: ThreadContext): Unit = {
    while (it.nonEmpty) {
      val pc = it.pop()
      externalInterpreter(pc, (), ctx)
    }
  }
}

private[choam] sealed abstract class RxnSyntax0 extends RxnInstances0 { this: Rxn.type =>
}

private[choam] sealed abstract class RxnInstances0 extends RxnInstances1 { self: Rxn.type =>

  implicit def arrowChoiceInstance: ArrowChoice[Rxn] =
    _arrowChoiceInstance

  private[this] val _arrowChoiceInstance: ArrowChoice[Rxn] = new ArrowChoice[Rxn] {

    def lift[A, B](f: A => B): Rxn[A, B] =
      Rxn.lift(f)

    override def id[A]: Rxn[A, A] =
      Rxn.lift(a => a)

    def compose[A, B, C](f: Rxn[B, C], g: Rxn[A, B]): Rxn[A, C] =
      g >>> f

    def first[A, B, C](fa: Rxn[A, B]): Rxn[(A, C), (B, C)] =
      fa.firstImpl

    def choose[A, B, C, D](f: Rxn[A, C])(g: Rxn[B, D]): Rxn[Either[A, B], Either[C, D]] = {
      computed[Either[A, B], Either[C, D]] {
        case Left(a) => (ret(a) >>> f).map(Left(_))
        case Right(b) => (ret(b) >>> g).map(Right(_))
      }
    }

    override def choice[A, B, C](f: Rxn[A, C], g: Rxn[B, C]): Rxn[Either[A, B], C] = {
      computed[Either[A, B], C] {
        case Left(a) => ret(a) >>> f
        case Right(b) => ret(b) >>> g
      }
    }

    override def lmap[A, B, X](fa: Rxn[A, B])(f: X => A): Rxn[X, B] =
      fa.lmap(f)

    override def rmap[A, B, C](fa: Rxn[A, B])(f: B => C): Rxn[A, C] =
      fa.rmap(f)
  }
}

private[choam] sealed abstract class RxnInstances1 extends RxnInstances2 { self: Rxn.type =>

  implicit def monadInstance[X]: Monad[Rxn[X, *]] = new Monad[Rxn[X, *]] {

    final override def pure[A](x: A): Rxn[X, A] =
      Rxn.ret(x)

    final override def flatMap[A, B](fa: Rxn[X, A])(f: A => Rxn[X, B]): Rxn[X, B] =
      fa.flatMap(f)

    final override def tailRecM[A, B](a: A)(f: A => Rxn[X, Either[A, B]]): Rxn[X, B] = {
      f(a).flatMap {
        case Left(a) => tailRecM(a)(f)
        case Right(b) => Rxn.ret(b)
      }
    }
  }
}

private[choam] sealed abstract class RxnInstances2 extends RxnInstances3 { self: Rxn.type =>

  implicit def localInstance[E]: Local[Rxn[E, *], E] = new Local[Rxn[E, *], E] {

    final override def applicative: Applicative[Rxn[E, *]] =
      self.monadInstance[E]

    final override def ask[E2 >: E]: Rxn[E, E2] =
      Rxn.identity[E]

    final override def local[A](fa: Rxn[E, A])(f: E => E): Rxn[E, A] =
      fa.lmap(f)
  }
}

private[choam] sealed abstract class RxnInstances3 { this: Rxn.type =>

  import cats.MonoidK

  implicit def monoidKInstance: MonoidK[λ[a => Rxn[a, a]]] = {
    new MonoidK[λ[a => Rxn[a, a]]] {

      final override def combineK[A](x: Rxn[A, A], y: Rxn[A, A]): Rxn[A, A] =
        x >>> y

      final override def empty[A]: Rxn[A, A] =
        Rxn.identity[A]
    }
  }
}
