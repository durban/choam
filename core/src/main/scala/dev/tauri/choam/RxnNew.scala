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

import cats.Monad
import cats.arrow.Arrow

import kcas.{ KCAS, ThreadContext, EMCASDescriptor }

sealed abstract class RxnNew[-A, +B] {

  import RxnNew._

  private[choam] def tag: Byte

  final def + [X <: A, Y >: B](that: RxnNew[X, Y]): RxnNew[X, Y] =
    new Choice[X, Y](this, that)

  final def >>> [C](that: RxnNew[B, C]): RxnNew[A, C] =
    new AndThen[A, B, C](this, that)

  final def × [C, D](that: RxnNew[C, D]): RxnNew[(A, C), (B, D)] =
    new AndAlso[A, B, C, D](this, that)

  final def * [X <: A, C](that: RxnNew[X, C]): RxnNew[X, (B, C)] =
    (this × that).contramap[X](x => (x, x))

  final def map[C](f: B => C): RxnNew[A, C] =
    this >>> lift(f)

  final def as[C](c: C): RxnNew[A, C] =
    this.map(_ => c)

  final def contramap[C](f: C => A): RxnNew[C, B] =
    lift(f) >>> this

  final def provide(a: A): RxnNew[Any, B] =
    contramap[Any](_ => a) // TODO: optimize

  final def *> [X <: A, C](that: RxnNew[X, C]): RxnNew[X, C] =
    this.productR(that)

  final def productR[X <: A, C](that: RxnNew[X, C]): RxnNew[X, C] =
    (this * that).map(_._2)

  final def first[C]: RxnNew[(A, C), (B, C)] =
    this × identity[C]

  final def second[C]: RxnNew[(C, A), (C, B)] =
    identity[C] × this

  // TODO: optimize
  final def flatMap[X <: A, C](f: B => RxnNew[X, C]): RxnNew[X, C] = {
    val self: RxnNew[X, (X, B)] = this.second[X].contramap[X](x => (x, x))
    val comp: RxnNew[(X, B), C] = computed[(X, B), C](xb => f(xb._2).provide(xb._1))
    self >>> comp
  }

  final def postCommit(pc: RxnNew[B, Unit]): RxnNew[A, B] =
    this >>> (new PostCommit[B](pc))

  final def unsafePerform(
    a: A,
    kcas: KCAS,
    maxBackoff: Int = 16,
    randomizeBackoff: Boolean = true
  ): B = {
    RxnNew.interpreter(
      this,
      a,
      ctx = kcas.currentContext(),
      maxBackoff = maxBackoff,
      randomizeBackoff = randomizeBackoff
    )
  }
}

object RxnNew extends RxnNewInstances0 {

  // API:

  def pure[A](a: A): RxnNew[Any, A] =
    ret(a)

  def ret[X, A](a: A): RxnNew[X, A] =
    lift[X, A](_ => a)

  def identity[A]: RxnNew[A, A] =
    lift(a => a)

  def lift[A, B](f: A => B): RxnNew[A, B] =
    new Lift(f)

  def computed[A, B](f: A => RxnNew[Any, B]): RxnNew[A, B] =
    new Computed(f)

  final object ref {

    def read[A](r: Ref[A]): RxnNew[Any, A] =
      upd[A, Any, A](r) { (oa, _) => (oa, oa) }

    def upd[A, B, C](r: Ref[A])(f: (A, B) => (A, C)): RxnNew[B, C] =
      new Upd(r, f)

    def update[A](r: Ref[A])(f: A => A): RxnNew[Any, Unit] =
      upd[A, Any, Unit](r) { (oa, _) => (f(oa), ()) }

    def updateWith[A](r: Ref[A])(f: A => RxnNew[Any, A]): RxnNew[Any, Unit] =
      updWith[A, Any, Unit](r) { (oa, _) => f(oa).map(na => (na, ())) }

    def getAndUpdate[A](r: Ref[A])(f: A => A): RxnNew[Any, A] =
      upd[A, Any, A](r) { (oa, _) => (f(oa), oa) }

    def getAndSet[A](r: Ref[A]): RxnNew[A, A] =
      upd[A, A, A](r) { (oa, na) => (na, oa) }

    def updWith[A, B, C](r: Ref[A])(f: (A, B) => RxnNew[Any, (A, C)]): RxnNew[B, C] = {
      val self: RxnNew[B, (A, B)] = RxnNew.unsafe.invisibleRead(r).first[B].contramap[B](b => ((), b))
      val comp: RxnNew[(A, B), C] = computed[(A, B), C] { case (oa, b) =>
        f(oa, b).flatMap {
          case (na, c) =>
            RxnNew.unsafe.cas(r, oa, na).map(_ => c)
        }
      }
      self >>> comp
    }
  }

  final object unsafe {

    def invisibleRead[A](r: Ref[A]): RxnNew[Any, A] =
      new InvisibleRead[A](r)

    def cas[A](r: Ref[A], ov: A, nv: A): RxnNew[Any, Unit] =
      new Cas[A](r, ov, nv)

    def retry[A, B]: RxnNew[A, B] =
      new AlwaysRetry[A, B]

    private[choam] def delay[A, B](uf: A => B): RxnNew[A, B] =
      lift(uf)

    // TODO: we need a better name
    def delayComputed[A, B](prepare: RxnNew[A, RxnNew[Any, B]]): RxnNew[A, B] =
      new DelayComputed[A, B](prepare)

    def exchanger[A, B]: RxnNew[Any, Exchanger[A, B]] =
      delay { _ => Exchanger.unsafe[A, B] }

    def exchange[A, B](ex: Exchanger[A, B]): RxnNew[A, B] =
      new Exchange[A, B](ex)
  }

  // Representation:

  /** Only the interpreter can use this */
  private final class Commit[A]()
    extends RxnNew[A, A] { private[choam] def tag = 0 }

  private final class AlwaysRetry[A, B]()
    extends RxnNew[A, B] { private[choam] def tag = 1 }

  private final class PostCommit[A](val pc: RxnNew[A, Unit])
      extends RxnNew[A, A] { private[choam] def tag = 2 }

  private final class Lift[A, B](val func: A => B)
    extends RxnNew[A, B] { private[choam] def tag = 3 }

  private final class Computed[A, B](val f: A => RxnNew[Any, B])
    extends RxnNew[A, B] { private[choam] def tag = 4 }

  // TODO: we need a better name
  private final class DelayComputed[A, B](val prepare: RxnNew[A, RxnNew[Any, B]])
    extends RxnNew[A, B] { private[choam] def tag = 5 }

  private final class Choice[A, B](val left: RxnNew[A, B], val right: RxnNew[A, B])
    extends RxnNew[A, B] { private[choam] def tag = 6 }

  private final class Cas[A](val ref: Ref[A], val ov: A, val nv: A)
    extends RxnNew[Any, Unit] { private[choam] def tag = 7 }

  private final class Upd[A, B, X](val ref: Ref[X], val f: (X, A) => (X, B))
    extends RxnNew[A, B] { private[choam] def tag = 8 }

  private final class InvisibleRead[A](val ref: Ref[A])
    extends RxnNew[Any, A] { private[choam] def tag = 9 }

  private final class Exchange[A, B](val exchanger: Exchanger[A, B])
    extends RxnNew[A, B] { private[choam] def tag = 10 }

  private final class AndThen[A, B, C](val left: RxnNew[A, B], val right: RxnNew[B, C])
    extends RxnNew[A, C] { private[choam] def tag = 11 }

  private final class AndAlso[A, B, C, D](val left: RxnNew[A, B], val right: RxnNew[C, D])
    extends RxnNew[(A, C), (B, D)] { private[choam] def tag = 12 }

  /** Only the interpreter can use this */
  private final class Done[A](val result: A)
    extends RxnNew[Any, A] { private[choam] def tag = 13 }

  // Interpreter:

  private[this] def newStack[A]() = {
    new ObjStack[A](initSize = 8)
  }

  private[this] final val ContAndThen = 0.toByte
  private[this] final val ContAndAlso = 1.toByte
  private[this] final val ContAndAlsoJoin = 2.toByte
  private[this] final val ContAfterDelayComp = 3.toByte
  private[this] final val ContPostCommit = 4.toByte
  private[this] final val ContAfterPostCommit = 5.toByte // TODO: rename

  private[choam] def interpreter[X, R](
    rxn: RxnNew[X, R],
    x: X,
    ctx: ThreadContext,
    maxBackoff: Int = 16,
    randomizeBackoff: Boolean = true
  ): R = {

    val kcas = ctx.impl

    var delayCompStorage: ObjStack[Any] = null

    var startRxn: RxnNew[Any, Any] = rxn.asInstanceOf[RxnNew[Any, Any]]
    var startA: Any = x

    var desc: EMCASDescriptor = kcas.start(ctx)

    val alts = newStack[Any]()

    val contT: ByteStack = new ByteStack(initSize = 8)
    val contK = newStack[Any]()
    val pc = newStack[RxnNew[Any, Unit]]()
    val commit = new Commit[R]
    contT.push(ContAfterPostCommit)
    contT.push(ContAndThen)
    contK.push(commit)

    var contTReset: Array[Byte] = contT.toArray()
    var contKReset: Any = contK.toArray()

    var a: Any = x
    var retries: Int = 0
    var isPostCommit: Boolean = false

    def saveEverything(): Unit = {
      if (delayCompStorage eq null) {
        delayCompStorage = newStack()
      }
      // save everything:
      saveAlt(null)
      delayCompStorage.push(alts.toArray())
      delayCompStorage.push(retries)
      delayCompStorage.push(isPostCommit)
      delayCompStorage.push(startRxn)
      delayCompStorage.push(startA)
      delayCompStorage.push(contTReset)
      delayCompStorage.push(contKReset)
      // reset state:
      desc = kcas.start(ctx)
      clearAlts()
      contT.clear()
      contK.clear()
      a = () : Any
      startA = () : Any
      retries = 0
      isPostCommit = false
    }

    def clearAlts(): Unit = {
      alts.clear()
    }

    def loadEverything(): Unit = {
      // TODO: this is a mess...
      contKReset = delayCompStorage.pop()
      contTReset = delayCompStorage.pop().asInstanceOf[Array[Byte]]
      startA = delayCompStorage.pop()
      startRxn = delayCompStorage.pop().asInstanceOf[RxnNew[Any, R]]
      isPostCommit = delayCompStorage.pop().asInstanceOf[Boolean]
      retries = delayCompStorage.pop().asInstanceOf[Int]
      alts.replaceWith(delayCompStorage.pop().asInstanceOf[Array[Any]])
      loadAlt()
      ()
    }

    def saveAlt(k: RxnNew[Any, R]): Unit = {
      alts.push(ctx.impl.snapshot(desc, ctx))
      alts.push(a)
      alts.push(contT.toArray())
      alts.push(contK.toArray())
      alts.push(pc.toArray())
      alts.push(k)
    }

    def loadAlt(): RxnNew[Any, R] = {
      val res = alts.pop().asInstanceOf[RxnNew[Any, R]]
      pc.replaceWithUnsafe(alts.pop().asInstanceOf[Array[Any]])
      contK.replaceWith(alts.pop().asInstanceOf[Array[Any]])
      contT.replaceWith(alts.pop().asInstanceOf[Array[Byte]])
      a = alts.pop()
      desc = alts.pop().asInstanceOf[EMCASDescriptor]
      res
    }

    def next(): RxnNew[Any, Any] = {
      (contT.pop() : @switch) match {
        case 0 => // ContAndThen
          val rrr = contK.pop()
          rrr.asInstanceOf[RxnNew[Any, Any]]
        case 1 => // ContAndAlso
          val savedA = a
          a = contK.pop()
          val res = contK.pop().asInstanceOf[RxnNew[Any, Any]]
          contK.push(savedA)
          res
        case 2 => // ContAndAlsoJoin
          val savedA = contK.pop()
          a = (savedA, a)
          next()
        case 3 => // ContAfterDelayComp
          val delayCompResult = contK.pop().asInstanceOf[RxnNew[Any, Any]]
          // continue with the rest:
          loadEverything()
          delayCompResult
        case 4 => // ContPostCommit
          val pcAction = contK.pop().asInstanceOf[RxnNew[Any, Any]]
          clearAlts()
          contTReset = contT.toArray()
          contKReset = contK.toArray()
          a = () : Any
          startA = () : Any
          startRxn = pcAction
          retries = 0
          isPostCommit = true
          desc = kcas.start(ctx)
          pcAction
        case 5 => // ContAfterPostCommit
          val res = contK.pop()
          new Done(res)
      }
    }

    def retry(): RxnNew[Any, Any] = {
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
    def loop[A, B](curr: RxnNew[A, B]): R = {
      (curr.tag : @switch) match {
        case 0 => // Commit
          if (kcas.tryPerform(desc, ctx)) {
            // ok, commit is done, but we still need to perform post-commit actions
            val res = a
            desc = kcas.start(ctx)
            a = () : Any
            if (!isPostCommit) {
              contK.push(res) // final result, Done will need it
            }
            isPostCommit = false
            while (pc.nonEmpty) {
              contK.push(commit) // commit the post-commit action
              contT.push(ContAndThen)
              contK.push(pc.pop()) // the post-commit action
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
          startRxn = c.prepare.asInstanceOf[RxnNew[Any, Any]]
          loop(c.prepare)
        case 6 => // Choice
          val c = curr.asInstanceOf[Choice[A, B]]
          saveAlt(c.right.asInstanceOf[RxnNew[Any, R]])
          loop(c.left)
        case 7 => // Cas
          val c = curr.asInstanceOf[Cas[Any]]
          val currVal = kcas.read(c.ref, ctx)
          if (equ(currVal, c.ov)) {
            desc = kcas.addCas(desc, c.ref, c.ov, c.nv, ctx)
            a = () : Unit
            loop(next())
          } else {
            contK.push(commit) // TODO: why?
            contT.push(ContAndThen) // TODO: why?
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

private[choam] sealed abstract class RxnNewInstances0 extends RxnNewInstances1 { this: RxnNew.type =>

  implicit def arrowInstance: Arrow[RxnNew] = new Arrow[RxnNew] {
    final override def compose[A, B, C](f: RxnNew[B, C], g: RxnNew[A, B]): RxnNew[A, C] =
      g >>> f
    final override def first[A, B, C](fa: RxnNew[A, B]): RxnNew[(A, C), (B, C)] =
      fa.first[C]
    final override def second[A, B, C](fa: RxnNew[A, B]): RxnNew[(C, A), (C, B)] =
      fa.second[C]
    final override def lift[A, B](f: A => B): RxnNew[A, B] =
      RxnNew.lift(f)
  }
}

private[choam] sealed abstract class RxnNewInstances1 extends RxnNewInstances2 { this: RxnNew.type =>

  implicit def monadInstance[X]: Monad[RxnNew[X, *]] = new Monad[RxnNew[X, *]] {
    final override def flatMap[A, B](fa: RxnNew[X, A])(f: A => RxnNew[X, B]): RxnNew[X, B] =
      fa.flatMap(f)
    final override def pure[A](a: A): RxnNew[X, A] =
      RxnNew.pure(a)
    final override def tailRecM[A, B](a: A)(f: A => RxnNew[X, Either[A, B]]): RxnNew[X, B] = {
      f(a).flatMap {
        case Left(a) => this.tailRecM(a)(f)
        case Right(b) => this.pure(b)
      }
    }
  }
}

private[choam] sealed abstract class RxnNewInstances2 { this: RxnNew.type =>

  implicit final class UnitSyntax[A](private val self: RxnNew[Unit, A]) {
    // TODO: this is temporary
    def run[F[_]](implicit F: Reactive[F], sF: cats.effect.kernel.Sync[F]): F[A] =
      sF.delay { RxnNew.interpreter(self, (), F.kcasImpl.currentContext()) }
  }
}
