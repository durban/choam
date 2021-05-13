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

  final def contramap[C](f: C => A): RxnNew[C, B] =
    lift(f) >>> this

  final def provide(a: A): RxnNew[Any, B] =
    contramap[Any](_ => a) // TODO: optimize

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

    def getAndUpdate[A](r: Ref[A])(f: A => A): RxnNew[Any, A] =
      upd[A, Any, A](r) { (oa, _) => (f(oa), oa) }

    def getAndSet[A](r: Ref[A]): RxnNew[A, A] =
      upd[A, A, A](r) { (oa, na) => (na, oa) }
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
  }

  // Representation:

  /** Only the interpreter can use this */
  private final class Commit[A]()
    extends RxnNew[A, A] { private[choam] def tag = 0 }

  private final class AlwaysRetry[A, B]()
    extends RxnNew[A, B] { private[choam] def tag = 1 }

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

  private final class AndThen[A, B, C](val left: RxnNew[A, B], val right: RxnNew[B, C])
    extends RxnNew[A, C] { private[choam] def tag = 11 }

  private final class AndAlso[A, B, C, D](val left: RxnNew[A, B], val right: RxnNew[C, D])
    extends RxnNew[(A, C), (B, D)] { private[choam] def tag = 12 }

  // Interpreter:

  private[this] final object ForSome {
    type x
    type y
    type z
    type p
    type q
  }

  private[this] def newStack[A]() = {
    new ObjStack[A](initSize = 8)
  }

  private[this] final val ContAndThen = 0.toByte
  private[this] final val ContAndAlso = 1.toByte
  private[this] final val ContAndAlsoJoin = 2.toByte
  private[this] final val ContAfterDelayComp = 3.toByte

  private[choam] def interpreter[X, R](
    rxn: RxnNew[X, R],
    x: X,
    ctx: ThreadContext,
    maxBackoff: Int = 16,
    randomizeBackoff: Boolean = true
  ): R = {

    val kcas = ctx.impl

    var delayCompStorage: ObjStack[Any] = null

    var desc: EMCASDescriptor = kcas.start(ctx)

    val altSnap = newStack[EMCASDescriptor]()
    val altA = newStack[ForSome.x]()
    val altK = newStack[RxnNew[ForSome.x, R]]()
    val altContT = newStack[Array[Byte]]()
    val altContK = newStack[Array[Any]]()

    val contT = newStack[Byte]() // TODO: ByteStack
    val contK = newStack[Any]()
    val commit = new Commit[R].asInstanceOf[RxnNew[ForSome.x, R]]
    contT.push(ContAndThen)
    contK.push(commit)

    var a: Any = x
    var retries: Int = 0

    def saveEverything(): Unit = {
      if (delayCompStorage eq null) {
        delayCompStorage = newStack()
      }
      // save everything:
      saveAlt(null)
      delayCompStorage.push(altSnap.toArray())
      delayCompStorage.push(altA.toArray())
      delayCompStorage.push(altK.toArray())
      delayCompStorage.push(altContT.toArray())
      delayCompStorage.push(altContK.toArray())
      delayCompStorage.push(retries)
      // reset state:
      desc = kcas.start(ctx)
      altSnap.clear()
      altA.clear()
      altK.clear()
      altContT.clear()
      altContK.clear()
      contT.clear()
      contT.push(ContAfterDelayComp)
      contK.clear()
      a = () : Any
      retries = 0
    }

    def loadEverything(): Unit = {
      // TODO: this is a mess...
      retries = delayCompStorage.pop().asInstanceOf[Int]
      altContK.replaceWithUnsafe(delayCompStorage.pop().asInstanceOf[Array[Any]])
      altContT.replaceWithUnsafe(delayCompStorage.pop().asInstanceOf[Array[Any]])
      altK.replaceWithUnsafe(delayCompStorage.pop().asInstanceOf[Array[Any]])
      altA.replaceWith(delayCompStorage.pop().asInstanceOf[Array[ForSome.x]])
      altSnap.replaceWithUnsafe(delayCompStorage.pop().asInstanceOf[Array[Any]])
      loadAlt()
      ()
    }

    def saveAlt(k: RxnNew[ForSome.x, R]): Unit = {
      altSnap.push(ctx.impl.snapshot(desc, ctx))
      altA.push(a.asInstanceOf[ForSome.x])
      altK.push(k)
      altContT.push(contT.toArray())
      altContK.push(contK.toArray())
    }

    def loadAlt(): RxnNew[ForSome.x, R] = {
      desc = altSnap.pop()
      a = altA.pop()
      contT.replaceWith(altContT.pop())
      contK.replaceWith(altContK.pop())
      altK.pop()
    }

    def next(): RxnNew[ForSome.x, R] = {
      (contT.pop() : @switch) match {
        case 0 => // ContAndThen
          contK.pop().asInstanceOf[RxnNew[ForSome.x, R]]
        case 1 => // ContAndAlso
          val savedA = a
          a = contK.pop()
          val res = contK.pop().asInstanceOf[RxnNew[ForSome.x, R]]
          contK.push(savedA)
          res
        case 2 => // ContAndAlsoJoin
          val savedA = contK.pop()
          a = (savedA, a)
          next()
        case 3 => // ContAfterDelayComp
          val delayCompResult = a
          // try to commit `prepare`:
          if (!kcas.tryPerform(desc, ctx)) {
            // retry `prepare`:
            retry()
          } else {
            // ok, continue with the rest:
            loadEverything()
            delayCompResult.asInstanceOf[RxnNew[ForSome.x, R]]
          }
      }
    }

    def retry(): RxnNew[ForSome.x, R] = {
      retries += 1
      if (altSnap.nonEmpty) {
        loadAlt()
      } else {
        desc = kcas.start(ctx)
        a = x
        contK.clear()
        contK.push(commit)
        contT.clear()
        contT.push(ContAndThen)
        spin()
        rxn.asInstanceOf[RxnNew[ForSome.x, R]]
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
            a.asInstanceOf[R]
          } else {
            loop(retry())
          }
        case 1 => // AlwaysRetry
          loop(retry())
        case 2 => // PostCommit
          sys.error("TODO") // TODO
        case 3 => // Lift
          val c = curr.asInstanceOf[Lift[A, B]]
          a = c.func(a.asInstanceOf[A])
          loop(next().asInstanceOf[RxnNew[B, ForSome.x]])
        case 4 => // Computed
          val c = curr.asInstanceOf[Computed[A, B]]
          val nxt = c.f(a.asInstanceOf[A])
          a = () : Any
          loop(nxt)
        case 5 => // DelayComputed
          val c = curr.asInstanceOf[DelayComputed[A, B]]
          val input = a
          saveEverything()
          a = input
          loop(c.prepare)
        case 6 => // Choice
          val c = curr.asInstanceOf[Choice[A, B]]
          saveAlt(c.right.asInstanceOf[RxnNew[ForSome.x, R]])
          loop(c.left)
        case 7 => // Cas
          val c = curr.asInstanceOf[Cas[ForSome.x]]
          val currVal = kcas.read(c.ref, ctx)
          if (equ(currVal, c.ov)) {
            desc = kcas.addCas(desc, c.ref, c.ov, c.nv, ctx)
            a = ()
            loop(next().asInstanceOf[RxnNew[Unit, R]])
          } else {
            loop(retry())
          }
        case 8 => // Upd
          val c = curr.asInstanceOf[Upd[A, B, ForSome.x]]
          val ox = kcas.read(c.ref, ctx)
          val (nx, b) = c.f(ox, a.asInstanceOf[A])
          kcas.addCas(desc, c.ref, ox, nx, ctx)
          a = b
          loop(next())
        case 9 => // InvisibleRead
          val c = curr.asInstanceOf[InvisibleRead[R]]
          a = kcas.read(c.ref, ctx)
          loop(next())
        case 10 => // GenExchange
          sys.error("TODO") // TODO
        case 11 => // AndThen
          val c = curr.asInstanceOf[AndThen[A, ForSome.x, B]]
          contT.push(ContAndThen)
          contK.push(c.right.asInstanceOf[RxnNew[ForSome.x, R]])
          loop(c.left)
        case 12 => // AndAlso
          val c = curr.asInstanceOf[AndAlso[ForSome.x, ForSome.y, ForSome.p, ForSome.q]]
          val xp = a.asInstanceOf[Tuple2[ForSome.x, ForSome.p]]
          // join:
          contT.push(ContAndAlsoJoin)
          // right:
          contT.push(ContAndAlso)
          contK.push(c.right)
          contK.push(xp._2)
          // left:
          a = xp._1
          loop(c.left)
        case t => // not implemented
          throw new UnsupportedOperationException(
            s"Not implemented tag ${t} for ${curr}"
          )
      }
    }

    val result: R = loop(rxn)
    // TODO: doPostCommit(postCommit, ctx)
    result
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
