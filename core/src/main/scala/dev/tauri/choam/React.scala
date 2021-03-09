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

// To avoid going through the aliases in scala._:
import scala.collection.immutable.{ Nil, :: }

import cats.{ Applicative, Monad }
import cats.arrow.ArrowChoice
import cats.mtl.Local

import kcas._

/**
 * A *partial* implementation of reagents, described in [Reagents: Expressing and
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
 * @see Other implementations:
 *
 * - https://github.com/aturon/Caper (Racket)
 * - https://github.com/ocamllabs/reagents (OCaml)
 */
sealed abstract class React[-A, +B] {

  import React._

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
    def go[C](partialResult: C, cont: React[C, B], rea: Reaction, desc: EMCASDescriptor, alts: List[SnapJump[_, B]], retries: Int): Success[B] = {
      cont.tryPerform(maxStackDepth, partialResult, rea, desc, ctx) match {
        case Retry =>
          if (randomizeBackoff) Backoff.backoffRandom(retries, maxBackoff)
          else Backoff.backoffConst(retries, maxBackoff)
          alts match {
            case _: Nil.type =>
              go(partialResult, cont, new Reaction(Nil, rea.token), kcas.start(ctx), alts, retries = retries + 1)
            case (h: SnapJump[x, B]) :: t =>
              go[x](h.value, h.react, h.ops, h.snap, t, retries = retries + 1)
          }
        case s @ Success(_, _) =>
          s
        case Jump(pr, k, rea, desc, alts2) =>
          go(pr, k, rea, desc, alts2 ++ alts, retries = retries)
          // TODO: optimize concat
      }
    }

    val res = go(a, this, new Reaction(Nil, new Token), kcas.start(ctx), Nil, retries = 0)
    res.reaction.postCommit.foreach { pc =>
      pc.unsafePerform((), kcas, maxBackoff = maxBackoff, randomizeBackoff = randomizeBackoff)
    }

    res.value
  }

  protected def tryPerform(n: Int, a: A, ops: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[B]

  protected final def maybeJump[C, Y >: B](
    n: Int,
    partialResult: C,
    cont: React[C, Y],
    ops: Reaction,
    desc: EMCASDescriptor,
    ctx: ThreadContext
  ): TentativeResult[Y] = {
    if (n <= 0) Jump(partialResult, cont, ops, desc, Nil)
    else cont.tryPerform(n - 1, partialResult, ops, desc, ctx)
  }

  final def + [X <: A, Y >: B](that: React[X, Y]): React[X, Y] =
    new Choice[X, Y](this, that)

  final def >>> [C](that: React[B, C]): React[A, C] = that match {
    case _: Commit[_] => this
    case _ => this.andThenImpl(that)
  }

  protected def andThenImpl[C](that: React[B, C]): React[A, C]

  final def * [X <: A, C](that: React[X, C]): React[X, (B, C)] = {
    (this × that).lmap[X](x => (x, x))
  }

  final def × [C, D](that: React[C, D]): React[(A, C), (B, D)] =
    this.productImpl(that)

  final def ? : React[A, Option[B]] =
    this.rmap(Some(_)) + ret[A, Option[B]](None)

  final def dup: React[A, (B, B)] =
    this.map { b => (b, b) }

  protected def productImpl[C, D](that: React[C, D]): React[(A, C), (B, D)]

  protected[choam] def firstImpl[C]: React[(A, C), (B, C)]

  // TODO: add `contramap` alias
  final def lmap[X](f: X => A): React[X, B] =
    lift(f) >>> this

  final def rmap[C](f: B => C): React[A, C] =
    this >>> lift(f)

  final def map[C](f: B => C): React[A, C] =
    rmap(f)

  // TODO: add `dimap`

  final def map2[X <: A, C, D](that: React[X, C])(f: (B, C) => D): React[X, D] =
    (this * that).map(f.tupled)

  // TODO: maybe rename to `void`
  final def discard: React[A, Unit] =
    this.rmap(_ => ()) // TODO: optimize

  final def flatMap[X <: A, C](f: B => React[X, C]): React[X, C] = {
    val self: React[X, (X, B)] = arrowChoiceInstance.second[X, B, X](this).lmap[X](x => (x, x))
    val comp: React[(X, B), C] = computed[(X, B), C](xb => f(xb._2).lmap[Unit](_ => xb._1))
    self >>> comp
  }

  private[choam] final def postCommit(pc: React[B, Unit]): React[A, B] =
    this >>> React.postCommit(pc)

  override def toString: String
}

object React extends ReactInstances0 {

  private[choam] final val maxStackDepth = 1024

  /** Old (slower) impl of `upd`, keep it for benchmarks */
  private[choam] def updDerived[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): React[B, C] = {
    val self: React[B, (A, B)] = r.invisibleRead.firstImpl[B].lmap[B](b => ((), b))
    val comp: React[(A, B), C] = computed[(A, B), C] { case (oa, b) =>
      val (na, c) = f(oa, b)
      r.cas(oa, na).rmap(_ => c)
    }
    self >>> comp
  }

  def upd[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): React[B, C] =
    new Upd(r, f, Commit[C]())

  def updWith[A, B, C](r: Ref[A])(f: (A, B) => React[Unit, (A, C)]): React[B, C] = {
    val self: React[B, (A, B)] = r.invisibleRead.firstImpl[B].lmap[B](b => ((), b))
    val comp: React[(A, B), C] = computed[(A, B), C] { case (oa, b) =>
      f(oa, b).flatMap {
        case (na, c) =>
          r.cas(oa, na).rmap(_ => c)
      }
    }
    self >>> comp
  }

  // TODO: generalize to more than 2
  def consistentRead[A, B](ra: Ref[A], rb: Ref[B]): React[Unit, (A, B)] = {
    ra.invisibleRead >>> computed[A, (A, B)] { a =>
      rb.invisibleRead >>> computed[B, (A, B)] { b =>
        (ra.cas(a, a) × rb.cas(b, b)).lmap[Unit] { _ => ((), ()) }.map { _ => (a, b) }
      }
    }
  }

  def swap[A](r1: Ref[A], r2: Ref[A]): React[Unit, Unit] = {
    r1.updWith[Unit, Unit] { (o1, _) =>
      r2.upd[Unit, A] { (o2, _) =>
        (o1, o2)
      }.map { o2 => (o2, ()) }
    }
  }

  def computed[A, B](f: A => React[Unit, B]): React[A, B] =
    new Computed[A, B, B](f, Commit[B]())

  private[choam] def cas[A](r: Ref[A], ov: A, nv: A): React[Unit, Unit] =
    new Cas[A, Unit](r, ov, nv, React.unit)

  private[choam] def invisibleRead[A](r: Ref[A]): React[Unit, A] =
    new Read(r, Commit[A]())

  private[choam] def postCommit[A](pc: React[A, Unit]): React[A, A] =
    new PostCommit[A, A](pc, Commit[A]())

  def lift[A, B](f: A => B): React[A, B] =
    new Lift(f, Commit[B]())

  private[choam] def delay[A, B](uf: A => B): React[A, B] =
    lift(uf)

  def identity[A]: React[A, A] =
    Commit[A]()

  private[this] val _unit =
    React.lift[Any, Unit] { _ => () }

  def unit[A]: React[A, Unit] =
    _unit

  def ret[X, A](a: A): React[X, A] =
    lift[X, A](_ => a)

  def pure[A](a: A): React[Unit, A] =
    ret(a)

  private[choam] def retry[A, B]: React[A, B] =
    AlwaysRetry()

  private[choam] def token: React[Unit, Token] =
    new Tok[Unit, Token, Token]((_, t) => t, Commit[Token]())

  def newRef[A](initial: A): React[Unit, Ref[A]] =
    delay[Unit, Ref[A]](_ => Ref.mk(initial))

  final object unsafe {

    // TODO: better name
    def delayComputed[A, B](prepare: React[A, React[Unit, B]]): React[A, B] =
      new DelayComputed[A, B, B](prepare, Commit[B]())

    def exchanger[A, B]: Action[Exchanger[A, B]] =
      Exchanger.apply[A, B]
  }

  implicit final class InvariantReactSyntax[A, B](private val self: React[A, B]) extends AnyVal {
    final def apply[F[_]](a: A)(implicit F: Reactive[F]): F[B] =
      F.run(self, a)
  }

  // TODO: if `Action[A]` is `React[Any, A]`, then this should be `AnyReactSyntax`
  implicit final class UnitReactSyntax[A](private val self: React[Unit, A]) extends AnyVal {

    // TODO: maybe this should be the default `flatMap`? (Not sure...)
    final def flatMapU[X, C](f: A => React[X, C]): React[X, C] = {
      val self2: React[X, (X, A)] =
        React.arrowChoiceInstance.second[Unit, A, X](self).lmap[X](x => (x, ()))
      val comp: React[(X, A), C] =
        React.computed[(X, A), C](xb => f(xb._2).lmap[Unit](_ => xb._1))
      self2 >>> comp
    }

    final def run[F[_]](implicit F: Reactive[F]): F[A] =
      F.run(self, ())

    final def unsafeRun(kcas: KCAS): A =
      self.unsafePerform((), kcas)

    final def void: React[Unit, Unit] =
      self.discard
  }

  implicit final class Tuple2ReactSyntax[A, B, C](private val self: React[A, (B, C)]) extends AnyVal {
    def left: React[A, B] =
      self.rmap(_._1)
    def right: React[A, C] =
      self.rmap(_._2)
    def split[X, Y](left: React[B, X], right: React[C, Y]): React[A, (X, Y)] =
      self >>> (left × right)
  }

  private[choam] final class Token

  // TODO: optimize building
  // TODO: rename to ReactionCtx, ReactionData, or something
  private[choam] final class Reaction(
    val postCommit: List[React[Unit, Unit]],
    val token: Token
  ) {

    def :: (act: React[Unit, Unit]): Reaction =
      new Reaction(postCommit = act :: this.postCommit, token = this.token)
  }

  protected[React] sealed trait TentativeResult[+A]
  protected[React] final case object Retry extends TentativeResult[Nothing]
  protected[React] final case class Success[+A](value: A, reaction: Reaction) extends TentativeResult[A]
  protected[React] final case class Jump[A, B](
    value: A,
    react: React[A, B],
    ops: Reaction,
    desc: EMCASDescriptor,
    alts: List[SnapJump[_, B]]
  ) extends TentativeResult[B] {

    def withAlt[X](alt: SnapJump[X, B]): Jump[A, B] = {
      this.copy(alts = alts :+ alt)
    }
  }

  protected[React] final case class SnapJump[A, +B](
    value: A,
    react: React[A, B],
    ops: Reaction,
    snap: EMCASDescriptor
  )

  private final class Commit[A]()
      extends React[A, A] {

    protected final def tryPerform(n: Int, a: A, reaction: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[A] = {
      if (ctx.impl.tryPerform(desc, ctx)) Success(a, reaction)
      else Retry
    }

    protected final def andThenImpl[C](that: React[A, C]): React[A, C] =
      that

    protected final def productImpl[C, D](that: React[C, D]): React[(A, C), (A, D)] = that match {
      case _: Commit[_] => Commit[(A, C)]()
      case _ => arrowChoiceInstance.second(that) // TODO: optimize
    }

    final override def firstImpl[C]: React[(A, C), (A, C)] =
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
      extends React[A, B] {

    protected final def tryPerform(n: Int, a: A, reaction: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[B] =
      Retry

    protected final def andThenImpl[C](that: React[B, C]): React[A, C] =
      AlwaysRetry()

    protected final def productImpl[C, D](that: React[C, D]): React[(A, C), (B, D)] =
      AlwaysRetry()

    final def firstImpl[C]: React[(A, C), (B, C)] =
      AlwaysRetry()

    final override def toString =
      "Retry"
  }

  private final object AlwaysRetry extends AlwaysRetry[Any, Any] {

    @inline
    def apply[A, B](): AlwaysRetry[A, B] =
      this.asInstanceOf[AlwaysRetry[A, B]]
  }

  private final class PostCommit[A, B](pc: React[A, Unit], k: React[A, B])
      extends React[A, B] {

    def tryPerform(n: Int, a: A, ops: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[B] =
      maybeJump(n, a, k, pc.lmap[Unit](_ => a) :: ops, desc, ctx)

    def andThenImpl[C](that: React[B, C]): React[A, C] =
      new PostCommit[A, C](pc, k >>> that)

    def productImpl[C, D](that: React[C, D]): React[(A, C), (B, D)] =
      new PostCommit[(A, C), (B, D)](pc.lmap[(A, C)](_._1), k × that)

    def firstImpl[C]: React[(A, C), (B, C)] =
      new PostCommit[(A, C), (B, C)](pc.lmap[(A, C)](_._1), k.firstImpl[C])

    override def toString =
      s"PostCommit(${pc}, ${k})"
  }

  private final class Lift[A, B, C](private val func: A => B, private val k: React[B, C])
      extends React[A, C] {

    def tryPerform(n: Int, a: A, ops: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[C] =
      maybeJump(n, func(a), k, ops, desc, ctx)

    def andThenImpl[D](that: React[C, D]): React[A, D] = {
      new Lift[A, B, D](func, k >>> that)
    }

    def productImpl[D, E](that: React[D, E]): React[(A, D), (C, E)] = that match {
      case _: Commit[_] =>
        new Lift[(A, D), (B, D), (C, D)](ad => (func(ad._1), ad._2), k.firstImpl)
      case l: Lift[D, x, E] =>
        new Lift[(A, D), (B, x), (C, E)](ad => (func(ad._1), l.func(ad._2)), k × l.k)
      case _ =>
        new Lift(ad => (func(ad._1), ad._2), k × that)
    }

    def firstImpl[D]: React[(A, D), (C, D)] =
      new Lift[(A, D), (B, D), (C, D)](ad => (func(ad._1), ad._2), k.firstImpl[D])

    override def toString =
      s"Lift(<function>, ${k})"
  }

  private final class Computed[A, B, C](f: A => React[Unit, B], k: React[B, C])
      extends React[A, C] {

    def tryPerform(n: Int, a: A, ops: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[C] =
      maybeJump(n, (), f(a) >>> k, ops, desc, ctx)

    def andThenImpl[D](that: React[C, D]): React[A, D] = {
      new Computed(f, k >>> that)
    }

    def productImpl[D, E](that: React[D, E]): React[(A, D), (C, E)] = {
      new Computed[(A, D), (B, D), (C, E)](
        ad => f(ad._1).rmap(b => (b, ad._2)),
        k × that
      )
    }

    def firstImpl[D]: React[(A, D), (C, D)] = {
      new Computed[(A, D), (B, D), (C, D)](
        ad => f(ad._1).firstImpl[D].lmap[Unit](_ => ((), ad._2)),
        k.firstImpl[D]
      )
    }

    override def toString =
      s"Computed(<function>, ${k})"
  }

  private final class DelayComputed[A, B, C](prepare: React[A, React[Unit, B]], k: React[B, C])
    extends React[A, C] {

    override def tryPerform(n: Int, a: A, ops: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[C] = {
      // Note: we're performing `prepare` here directly;
      // as a consequence of this, `prepare` will not
      // be part of the atomic reaction, but it runs here
      // as a side-effect.
      val r: React[Unit, B] = prepare.unsafePerform(
        a,
        ctx.impl,
        maxBackoff = ctx.maxBackoff,
        randomizeBackoff = ctx.randomizeBackoff
      )
      maybeJump(n, (), r >>> k, ops, desc, ctx)
    }

    override def andThenImpl[D](that: React[C, D]): React[A, D] = {
      new DelayComputed(prepare, k >>> that)
    }

    override def productImpl[D, E](that: React[D, E]): React[(A, D), (C, E)] = {
      new DelayComputed[(A, D), (B, D), (C, E)](
        prepare.firstImpl[D].map { case (r, d) =>
          r.map { b => (b, d) }
        },
        k × that
      )
    }

    override def firstImpl[D]: React[(A, D), (C, D)] = {
      new DelayComputed[(A, D), (B, D), (C, D)](
        prepare.firstImpl[D].map { case (r, d) =>
          r.map { b => (b, d) }
        },
        k.firstImpl[D]
      )
    }

    override def toString =
      s"DelayComputed(${prepare}, ${k})"
  }

  private final class Choice[A, B](first: React[A, B], second: React[A, B])
      extends React[A, B] {

    def tryPerform(n: Int, a: A, ops: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[B] = {
      if (n <= 0) {
        Jump(a, this, ops, desc, Nil)
      } else {
        val snap = ctx.impl.snapshot(desc, ctx)
        first.tryPerform(n - 1, a, ops, desc, ctx) match {
          case _: Retry.type =>
            second.tryPerform(n - 1, a, ops, snap, ctx)
          case jmp: Jump[a, B] =>
            // TODO: optimize building `alts`
            jmp.withAlt(SnapJump(a, second, ops, snap))
          case ok =>
            ok
        }
      }
    }

    def andThenImpl[C](that: React[B, C]): React[A, C] =
      new Choice[A, C](first >>> that, second >>> that)

    def productImpl[C, D](that: React[C, D]): React[(A, C), (B, D)] =
      new Choice[(A, C), (B, D)](first × that, second × that)

    def firstImpl[C]: React[(A, C), (B, C)] =
      new Choice[(A, C), (B, C)](first.firstImpl, second.firstImpl)

    override def toString =
      s"Choice(${first}, ${second})"
  }

  private abstract class GenCas[A, B, C, D](ref: Ref[A], ov: A, nv: A, k: React[C, D])
      extends React[B, D] { self =>

    /** Must be pure */
    protected def transform(a: A, b: B): C

    protected final def tryPerform(n: Int, b: B, pc: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[D] = {
      val a = ctx.impl.read(ref, ctx)
      if (equ(a, ov)) {
        maybeJump(n, transform(a, b), k, pc, ctx.impl.addCas(desc, ref, ov, nv, ctx), ctx)
      } else {
        Retry
      }
    }

    final def andThenImpl[E](that: React[D, E]): React[B, E] = {
      new GenCas[A, B, C, E](ref, ov, nv, k >>> that) {
        protected def transform(a: A, b: B): C =
          self.transform(a, b)
      }
    }

    final def productImpl[E, F](that: React[E, F]): React[(B, E), (D, F)] = {
      new GenCas[A, (B, E), (C, E), (D, F)](ref, ov, nv, k × that) {
        protected def transform(a: A, be: (B, E)): (C, E) =
          (self.transform(a, be._1), be._2)
      }
    }

    final def firstImpl[E]: React[(B, E), (D, E)] = {
      new GenCas[A, (B, E), (C, E), (D, E)](ref, ov, nv, k.firstImpl[E]) {
        protected def transform(a: A, be: (B, E)): (C, E) =
          (self.transform(a, be._1), be._2)
      }
    }

    final override def toString =
      s"GenCas(${ref}, ${ov}, ${nv}, ${k})"
  }

  private final class Cas[A, B](ref: Ref[A], ov: A, nv: A, k: React[A, B])
      extends GenCas[A, Unit, A, B](ref, ov, nv, k) {

    def transform(a: A, b: Unit): A =
      a
  }

  private final class Tok[A, B, C](t: (A, Token) => B, k: React[B, C])
    extends React[A, C] {

    override protected def tryPerform(n: Int, a: A, ops: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[C] =
      maybeJump(n, t(a, ops.token), k, ops, desc, ctx)

    override protected def andThenImpl[D](that: React[C, D]): React[A, D] =
      new Tok(t, k.andThenImpl(that))

    override protected def productImpl[D, E](that: React[D, E]): React[(A, D), (C, E)] =
      new Tok(tFirst[D], k.productImpl(that))

    override protected[choam] def firstImpl[D]: React[(A, D), (C, D)] =
      new Tok[(A, D), (B, D), (C, D)](tFirst[D], k.firstImpl[D])

    private[this] def tFirst[D](ad: (A, D), tok: Token): (B, D) =
      (t(ad._1, tok), ad._2)

    final override def toString =
      s"Tok(${k})"
  }

  private final class Upd[A, B, C, X](ref: Ref[X], f: (X, A) => (X, B), k: React[B, C])
    extends React[A, C] { self =>

    override protected def tryPerform(n: Int, a: A, ops: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[C] = {
      val ox = ctx.impl.read(ref, ctx)
      val (nx, b) = f(ox, a)
      maybeJump(n, b, k, ops, ctx.impl.addCas(desc, ref, ox, nx, ctx), ctx)
    }

    override protected def andThenImpl[D](that: React[C, D]): React[A, D] =
      new Upd[A, B, D, X](ref, f, k.andThenImpl(that))

    override protected def productImpl[D, E](that: React[D, E]): React[(A, D), (C, E)] =
      new Upd[(A, D), (B, D), (C, E), X](ref, this.fFirst[D], k.productImpl(that))

    override protected[choam] def firstImpl[D]: React[(A, D), (C, D)] =
      new Upd[(A, D), (B, D), (C, D), X](ref, this.fFirst[D], k.firstImpl[D])

    final override def toString =
      s"Upd(${ref}, <function>, ${k})"

    private[this] def fFirst[D](ox: X, ad: (A, D)): (X, (B, D)) = {
      val (x, b) = this.f(ox, ad._1)
      (x, (b, ad._2))
    }
  }

  private abstract class GenRead[A, B, C, D](ref: Ref[A], k: React[C, D])
      extends React[B, D] { self =>

    /** Must be pure */
    protected def transform(a: A, b: B): C

    protected final def tryPerform(n: Int, b: B, ops: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[D] = {
      val a = ctx.impl.read(ref, ctx)
      maybeJump(n, transform(a, b), k, ops, desc, ctx)
    }

    final def andThenImpl[E](that: React[D, E]): React[B, E] = {
      new GenRead[A, B, C, E](ref, k >>> that) {
        protected def transform(a: A, b: B): C =
          self.transform(a, b)
      }
    }

    final def productImpl[E, F](that: React[E, F]): React[(B, E), (D, F)] = {
      new GenRead[A, (B, E), (C, E), (D, F)](ref, k × that) {
        protected def transform(a: A, be: (B, E)): (C, E) =
          (self.transform(a, be._1), be._2)
      }
    }

    final def firstImpl[E]: React[(B, E), (D, E)] = {
      new GenRead[A, (B, E), (C, E), (D, E)](ref, k.firstImpl[E]) {
        protected def transform(a: A, be: (B, E)): (C, E) =
          (self.transform(a, be._1), be._2)
      }
    }

    final override def toString =
      s"GenRead(${ref}, ${k})"
  }

  private final class Read[A, B](ref: Ref[A], k: React[A, B])
      extends GenRead[A, Unit, A, B](ref, k) {

    def transform(a: A, b: Unit): A =
      a
  }

  private[choam] sealed abstract class Exchange[A, B, C, D, E](
    exchanger: Exchanger[A, B],
    k: React[D, E]
  ) extends React[C, E] { self =>

    protected def transform1(c: C): A

    protected def transform2(b: B, c: C): D

    override protected def tryPerform(n: Int, c: C, ops: Reaction, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[E] = {
      val retries = 42 // TODO
      val msg = Exchanger.Msg[A, B, E](
        value = transform1(c),
        cont = k.lmap[B](b => self.transform2(b, c)),
        ops = ops,
        desc = desc // TODO: not threadsafe
      )
      // TODO: An `Exchange(...) + Exchange(...)` should post the
      // TODO: same offer to both exchangers, so that fulfillers
      // TODO: can race there.
      this.exchanger.tryExchange(msg, ctx, retries) match {
        case Some(contMsg) =>
          maybeJump(n, (), contMsg.cont, contMsg.ops, contMsg.desc, ctx)
        case None =>
          // TODO: adjust contention management in exchanger
          Retry
      }
    }

    override protected def andThenImpl[F](that: React[E, F]): React[C, F] = {
      new Exchange[A, B, C, D, F](this.exchanger, this.k >>> that) {
        protected override def transform1(c: C): A =
          self.transform1(c)
        protected override def transform2(b: B, c: C): D =
          self.transform2(b, c)
      }
    }

    override protected def productImpl[F, G](that: React[F, G]): React[(C, F), (E, G)] = {
      new Exchange[A, B, (C, F), (D, F), (E, G)](exchanger, k.productImpl(that)) {
        protected override def transform1(cf: (C, F)): A =
          self.transform1(cf._1)
        protected override def transform2(b: B, cf: (C, F)): (D, F) = {
          val d = self.transform2(b, cf._1)
          (d, cf._2)
        }
      }
    }

    override protected[choam] def firstImpl[F]: React[(C, F), (E, F)] = {
      new Exchange[A, B, (C, F), (D, F), (E, F)](exchanger, k.firstImpl[F]) {
        protected override def transform1(cf: (C, F)): A =
          self.transform1(cf._1)
        protected override def transform2(b: B, cf: (C, F)): (D, F) = {
          val d = self.transform2(b, cf._1)
          (d, cf._2)
        }
      }
    }

    final override def toString: String =
      s"Exchange(${exchanger}, ${k})"
  }

  private[choam] final class SimpleExchange[A, B, C](
    exchanger: Exchanger[A, B],
    k: React[B, C]
  ) extends Exchange[A, B, A, B, C](exchanger, k) {

    override protected def transform1(a: A): A =
      a

    override protected def transform2(b: B, a: A): B =
      b
  }
}

sealed abstract class ReactInstances0 extends ReactInstances1 { self: React.type =>

  implicit val arrowChoiceInstance: ArrowChoice[React] = new ArrowChoice[React] {

    def lift[A, B](f: A => B): React[A, B] =
      React.lift(f)

    override def id[A]: React[A, A] =
      React.lift(a => a)

    def compose[A, B, C](f: React[B, C], g: React[A, B]): React[A, C] =
      g >>> f

    def first[A, B, C](fa: React[A, B]): React[(A, C), (B, C)] =
      fa.firstImpl

    def choose[A, B, C, D](f: React[A, C])(g: React[B, D]): React[Either[A, B], Either[C, D]] = {
      computed[Either[A, B], Either[C, D]] {
        case Left(a) => (ret(a) >>> f).map(Left(_))
        case Right(b) => (ret(b) >>> g).map(Right(_))
      }
    }

    override def choice[A, B, C](f: React[A, C], g: React[B, C]): React[Either[A, B], C] = {
      computed[Either[A, B], C] {
        case Left(a) => ret(a) >>> f
        case Right(b) => ret(b) >>> g
      }
    }

    override def lmap[A, B, X](fa: React[A, B])(f: X => A): React[X, B] =
      fa.lmap(f)

    override def rmap[A, B, C](fa: React[A, B])(f: B => C): React[A, C] =
      fa.rmap(f)
  }
}

sealed abstract class ReactInstances1 extends ReactInstances2 { self: React.type =>

  implicit def monadInstance[X]: Monad[React[X, *]] = new Monad[React[X, *]] {

    final override def pure[A](x: A): React[X, A] =
      React.ret(x)

    final override def flatMap[A, B](fa: React[X, A])(f: A => React[X, B]): React[X, B] =
      fa.flatMap(f)

    final override def tailRecM[A, B](a: A)(f: A => React[X, Either[A, B]]): React[X, B] = {
      f(a).flatMap {
        case Left(a) => tailRecM(a)(f)
        case Right(b) => React.ret(b)
      }
    }
  }
}

sealed abstract class ReactInstances2 { self: React.type =>

  implicit def localInstance[E]: Local[React[E, *], E] = new Local[React[E, *], E] {

    final override def applicative: Applicative[React[E, *]] =
      self.monadInstance[E]

    final override def ask[E2 >: E]: React[E, E2] =
      React.identity[E]

    final override def local[A](fa: React[E, A])(f: E => E): React[E, A] =
      fa.lmap(f)
  }
}
