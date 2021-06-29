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

import java.util.Arrays

import cats.{ Monad, Applicative, MonoidK }
import cats.arrow.ArrowChoice
import cats.mtl.Local

import kcas.{ KCAS, ThreadContext, EMCASDescriptor }

sealed abstract class Rxn[-A, +B] {

  import Rxn._

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
    this.map(Some(_)) + ret[A, Option[B]](None)

  final def map[C](f: B => C): Rxn[A, C] =
    this >>> lift(f)

  final def as[C](c: C): Rxn[A, C] =
    this.map(_ => c)

  final def void: Rxn[A, Unit] =
    this.as(())

  final def contramap[C](f: C => A): Rxn[C, B] =
    lift(f) >>> this

  final def provide(a: A): Rxn[Any, B] =
    contramap[Any](_ => a) // TODO: optimize

  final def toFunction: A => Axn[B] = { (a: A) =>
    this.provide(a)
  }

  final def map2[X <: A, C, D](that: Rxn[X, C])(f: (B, C) => D): Rxn[X, D] =
    (this * that).map(f.tupled)

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

  final def postCommit(pc: Rxn[B, Unit]): Rxn[A, B] =
    this >>> Rxn.postCommit[B](pc)

  final def unsafePerform(
    a: A,
    kcas: KCAS,
    maxBackoff: Int = 16,
    randomizeBackoff: Boolean = true
  ): B = {
    Rxn.interpreter(
      this,
      a,
      ctx = kcas.currentContext(),
      maxBackoff = maxBackoff,
      randomizeBackoff = randomizeBackoff
    )
  }
}

object Rxn extends RxnInstances0 {

  // API:

  def pure[A](a: A): Rxn[Any, A] =
    ret(a)

  def ret[X, A](a: A): Rxn[X, A] =
    lift[X, A](_ => a)

  def identity[A]: Rxn[A, A] =
    lift(a => a)

  def lift[A, B](f: A => B): Rxn[A, B] =
    new Lift(f)

  def unit[A]: Rxn[A, Unit] =
    lift(_ => ())

  def computed[A, B](f: A => Rxn[Any, B]): Rxn[A, B] =
    new Computed(f)

  final def postCommit[A](pc: Rxn[A, Unit]): Rxn[A, A] =
    new PostCommit[A](pc)

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

    def read[A](r: Ref[A]): Rxn[Any, A] =
      upd[A, Any, A](r) { (oa, _) => (oa, oa) }

    def upd[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): Rxn[B, C] =
      new Upd(r, f)

    /** Old (slower) impl of `upd`, keep it for benchmarks */
    private[choam] def updDerived[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): Rxn[B, C] = {
      val self: Rxn[B, (A, B)] = r.unsafeInvisibleRead.first[B].contramap[B](b => ((), b))
      val comp: Rxn[(A, B), C] = computed[(A, B), C] { case (oa, b) =>
        val (na, c) = f(oa, b)
        r.unsafeCas(oa, na).map(_ => c)
      }
      self >>> comp
    }

    def update[A](r: Ref[A])(f: A => A): Rxn[Any, Unit] =
      upd[A, Any, Unit](r) { (oa, _) => (f(oa), ()) }

    def updateWith[A](r: Ref[A])(f: A => Rxn[Any, A]): Rxn[Any, Unit] =
      updWith[A, Any, Unit](r) { (oa, _) => f(oa).map(na => (na, ())) }

    def getAndUpdate[A](r: Ref[A])(f: A => A): Rxn[Any, A] =
      upd[A, Any, A](r) { (oa, _) => (f(oa), oa) }

    def getAndSet[A](r: Ref[A]): Rxn[A, A] =
      upd[A, A, A](r) { (oa, na) => (na, oa) }

    def updWith[A, B, C](r: Ref[A])(f: (A, B) => Rxn[Any, (A, C)]): Rxn[B, C] = {
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

    def invisibleRead[A](r: Ref[A]): Rxn[Any, A] =
      new InvisibleRead[A](r)

    def cas[A](r: Ref[A], ov: A, nv: A): Rxn[Any, Unit] =
      new Cas[A](r, ov, nv)

    def retry[A, B]: Rxn[A, B] =
      new AlwaysRetry[A, B]

    private[choam] def delay[A, B](uf: A => B): Rxn[A, B] =
      lift(uf)

    // TODO: we need a better name
    def delayComputed[A, B](prepare: Rxn[A, Rxn[Any, B]]): Rxn[A, B] =
      new DelayComputed[A, B](prepare)

    def exchanger[A, B]: Rxn[Any, Exchanger[A, B]] =
      delay { _ => Exchanger.unsafe[A, B] }

    def exchange[A, B](ex: Exchanger[A, B]): Rxn[A, B] =
      new Exchange[A, B](ex)
  }

  // Representation:

  /** Only the interpreter can use this */
  private final class Commit[A]()
    extends Rxn[A, A] { private[choam] def tag = 0 }

  private final class AlwaysRetry[A, B]()
    extends Rxn[A, B] { private[choam] def tag = 1 }

  private final class PostCommit[A](val pc: Rxn[A, Unit])
      extends Rxn[A, A] { private[choam] def tag = 2 }

  private final class Lift[A, B](val func: A => B)
    extends Rxn[A, B] { private[choam] def tag = 3 }

  private final class Computed[A, B](val f: A => Rxn[Any, B])
    extends Rxn[A, B] { private[choam] def tag = 4 }

  // TODO: we need a better name
  private final class DelayComputed[A, B](val prepare: Rxn[A, Rxn[Any, B]])
    extends Rxn[A, B] { private[choam] def tag = 5 }

  private final class Choice[A, B](val left: Rxn[A, B], val right: Rxn[A, B])
    extends Rxn[A, B] { private[choam] def tag = 6 }

  private final class Cas[A](val ref: Ref[A], val ov: A, val nv: A)
    extends Rxn[Any, Unit] { private[choam] def tag = 7 }

  private final class Upd[A, B, X](val ref: Ref[X], val f: (X, A) => (X, B))
    extends Rxn[A, B] { private[choam] def tag = 8 }

  private final class InvisibleRead[A](val ref: Ref[A])
    extends Rxn[Any, A] { private[choam] def tag = 9 }

  private final class Exchange[A, B](val exchanger: Exchanger[A, B])
    extends Rxn[A, B] { private[choam] def tag = 10 }

  private final class AndThen[A, B, C](val left: Rxn[A, B], val right: Rxn[B, C])
    extends Rxn[A, C] { private[choam] def tag = 11 }

  private final class AndAlso[A, B, C, D](val left: Rxn[A, B], val right: Rxn[C, D])
    extends Rxn[(A, C), (B, D)] { private[choam] def tag = 12 }

  /** Only the interpreter can use this */
  private final class Done[A](val result: A)
    extends Rxn[Any, A] { private[choam] def tag = 13 }

  // Interpreter:

  private[this] def newStack[A]() = {
    new ObjStack[A](initSize = 8)
  }

  private[this] final val ContAndThen = 0.toByte
  private[this] final val ContAndAlso = 1.toByte
  private[this] final val ContAndAlsoJoin = 2.toByte
  private[this] final val ContAfterDelayComp = 3.toByte
  private[this] final val ContPostCommit = 4.toByte
  private[this] final val ContAfterPostCommit = 5.toByte // TODO: rename(?)
  private[this] final val ContCommitPostCommit = 6.toByte

  private[this] final class PostCommitResultMarker // TODO: make this a java enum
  private[this] val postCommitResultMarker =
    new PostCommitResultMarker

  private[choam] def interpreter[X, R](
    rxn: Rxn[X, R],
    x: X,
    ctx: ThreadContext,
    maxBackoff: Int = 16,
    randomizeBackoff: Boolean = true
  ): R = {

    val kcas = ctx.impl

    var delayCompStorage: ObjStack[Any] = null

    var startRxn: Rxn[Any, Any] = rxn.asInstanceOf[Rxn[Any, Any]]
    var startA: Any = x

    var desc: EMCASDescriptor = kcas.start(ctx)

    val alts = newStack[Any]()

    val contT: ByteStack = new ByteStack(initSize = 8)
    val contK = newStack[Any]()
    val pc = newStack[Rxn[Any, Unit]]()
    val commit = new Commit[R]
    contT.push(ContAfterPostCommit)
    contT.push(ContAndThen)
    contK.push(commit)

    var contTReset: Array[Byte] = contT.toArray()
    var contKReset: Array[Any] = contK.toArray()

    var a: Any = x
    var retries: Int = 0

    def saveEverything(): Unit = {
      if (delayCompStorage eq null) {
        delayCompStorage = newStack()
      }
      // save everything:
      saveAlt(null)
      delayCompStorage.push(alts.toArray())
      delayCompStorage.push(retries)
      delayCompStorage.push(startRxn)
      delayCompStorage.push(startA)
      delayCompStorage.push(Arrays.copyOf(contTReset, contTReset.length))
      delayCompStorage.push(Arrays.copyOf(contKReset.asInstanceOf[Array[AnyRef]], contKReset.length))
      // reset state:
      desc = kcas.start(ctx)
      clearAlts()
      contT.clear()
      contK.clear()
      a = () : Any
      startA = () : Any
      retries = 0
    }

    def clearAlts(): Unit = {
      alts.clear()
    }

    def loadEverything(): Unit = {
      // TODO: this is a mess...
      contKReset = delayCompStorage.pop().asInstanceOf[Array[Any]]
      contTReset = delayCompStorage.pop().asInstanceOf[Array[Byte]]
      startA = delayCompStorage.pop()
      startRxn = delayCompStorage.pop().asInstanceOf[Rxn[Any, R]]
      retries = delayCompStorage.pop().asInstanceOf[Int]
      alts.replaceWith(delayCompStorage.pop().asInstanceOf[Array[Any]])
      loadAlt()
      ()
    }

    def saveAlt(k: Rxn[Any, R]): Unit = {
      alts.push(ctx.impl.snapshot(desc, ctx))
      alts.push(a)
      alts.push(contT.toArray())
      alts.push(contK.toArray())
      alts.push(pc.toArray())
      alts.push(k)
    }

    def loadAlt(): Rxn[Any, R] = {
      val res = alts.pop().asInstanceOf[Rxn[Any, R]]
      pc.replaceWithUnsafe(alts.pop().asInstanceOf[Array[Any]])
      contK.replaceWith(alts.pop().asInstanceOf[Array[Any]])
      contT.replaceWith(alts.pop().asInstanceOf[Array[Byte]])
      a = alts.pop()
      desc = alts.pop().asInstanceOf[EMCASDescriptor]
      res
    }

    def next(): Rxn[Any, Any] = {
      (contT.pop() : @switch) match {
        case 0 => // ContAndThen
          val rrr = contK.pop()
          rrr.asInstanceOf[Rxn[Any, Any]]
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
          contTReset = contT.toArray()
          contKReset = contK.toArray()
          a = () : Any
          startA = () : Any
          startRxn = pcAction
          retries = 0
          desc = kcas.start(ctx)
          pcAction
        case 5 => // ContAfterPostCommit
          val res = popFinalResult()
          assert(contK.isEmpty)
          assert(contT.isEmpty)
          new Done(res)
        case 6 => // ContCommitPostCommit
          a = postCommitResultMarker : Any
          commit.asInstanceOf[Rxn[Any, Any]]
      }
    }

    def popFinalResult(): Any = {
      val r = contK.pop()
      assert(!equ(r, postCommitResultMarker))
      r
    }

    def retry(): Rxn[Any, Any] = {
      retries += 1
      if (alts.nonEmpty) {
        loadAlt()
      } else {
        // really restart:
        desc = kcas.start(ctx)
        a = startA
        contT.replaceWith(contTReset)
        contK.replaceWithUnsafe(contKReset.asInstanceOf[Array[Any]])
        pc.clear()
        spin()
        startRxn
      }
    }

    def spin(): Unit = {
      if (randomizeBackoff) Backoff.backoffRandom(retries, maxBackoff)
      else Backoff.backoffConst(retries, maxBackoff)
    }

    @tailrec
    def loop[A, B](curr: Rxn[A, B]): R = {
      (curr.tag : @switch) match {
        case 0 => // Commit
          if (kcas.tryPerform(desc, ctx)) {
            // ok, commit is done, but we still need to perform post-commit actions
            val res = a
            desc = kcas.start(ctx)
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
          val c = curr.asInstanceOf[DelayComputed[A, B]]
          val input = a
          saveEverything()
          contT.push(ContAfterDelayComp)
          contT.push(ContAndThen) // commit `prepare`
          contK.push(commit)
          contTReset = contT.toArray()
          contKReset = contK.toArray()
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
          val currVal = kcas.read(c.ref, ctx)
          if (equ(currVal, c.ov)) {
            desc = kcas.addCas(desc, c.ref, c.ov, c.nv, ctx)
            a = () : Unit
            loop(next())
          } else {
            loop(retry())
          }
        case 8 => // Upd
          val c = curr.asInstanceOf[Upd[A, B, Any]]
          val ox = kcas.read(c.ref, ctx)
          val (nx, b) = c.f(ox, a.asInstanceOf[A])
          desc = kcas.addCas(desc, c.ref, ox, nx, ctx)
          a = b
          loop(next())
        case 9 => // InvisibleRead
          val c = curr.asInstanceOf[InvisibleRead[B]]
          a = kcas.read(c.ref, ctx)
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
        case t => // not implemented
          throw new UnsupportedOperationException(
            s"Not implemented tag ${t} for ${curr}"
          )
      }
    }

    loop(startRxn)
  }
}

private[choam] sealed abstract class RxnInstances0 extends RxnInstances1 { this: Rxn.type =>

  implicit def arrowChoiceInstance: ArrowChoice[Rxn] = new ArrowChoice[Rxn] {

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

  implicit def localInstance[E]: Local[Rxn[E, *], E] = new Local[Rxn[E, *], E] {
    final override def applicative: Applicative[Rxn[E, *]] =
      self.monadInstance[E]
    final override def ask[E2 >: E]: Rxn[E, E2] =
      Rxn.identity[E]
    final override def local[A](fa: Rxn[E, A])(f: E => E): Rxn[E, A] =
      fa.contramap(f)
  }
}

private[choam] sealed abstract class RxnInstances2 extends RxnInstances3 { this: Rxn.type =>

  implicit def monadInstance[X]: Monad[Rxn[X, *]] = new Monad[Rxn[X, *]] {
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

private[choam] sealed abstract class RxnInstances3 extends RxnInstances4 { this: Rxn.type =>

  implicit def monoidKInstance: MonoidK[λ[a => Rxn[a, a]]] = {
    new MonoidK[λ[a => Rxn[a, a]]] {
      final override def combineK[A](x: Rxn[A, A], y: Rxn[A, A]): Rxn[A, A] =
        x >>> y
      final override def empty[A]: Rxn[A, A] =
        Rxn.identity[A]
    }
  }
}

private[choam] sealed abstract class RxnInstances4 extends RxnInstances5 { this: Rxn.type =>

  implicit final class InvariantSyntax[A, B](private val self: Rxn[A, B]) {
    final def apply[F[_]](a: A)(implicit F: Reactive[F]): F[B] =
      F.run(self, a)
  }
}

private[choam] sealed abstract class RxnInstances5 { this: Rxn.type =>

  implicit final class UnitSyntax[A](private val self: Rxn[Unit, A]) {

    final def run[F[_]](implicit F: Reactive[F]): F[A] =
      F.run(self, ())

    final def unsafeRun(
      kcas: KCAS,
      maxBackoff: Int = 16,
      randomizeBackoff: Boolean = true
    ): A = {
      self.unsafePerform((), kcas, maxBackoff = maxBackoff, randomizeBackoff = randomizeBackoff)
    }
  }
}
