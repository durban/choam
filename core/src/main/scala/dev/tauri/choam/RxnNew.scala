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

import kcas.{ KCAS, ThreadContext, EMCASDescriptor }

sealed abstract class RxnNew[-A, +B] {

  import RxnNew._

  private[choam] def tag: Byte

  final def + [X <: A, Y >: B](that: RxnNew[X, Y]): RxnNew[X, Y] =
    new Choice[X, Y](this, that)

  final def >>> [C](that: RxnNew[B, C]): RxnNew[A, C] =
    new AndThen[A, B, C](this, that)

  final def map[C](f: B => C): RxnNew[A, C] =
    this >>> lift(f)

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

object RxnNew {

  // API:

  def lift[A, B](f: A => B): RxnNew[A, B] =
    new Lift(f)

  final object unsafe {

    def cas[A](r: Ref[A], ov: A, nv: A): RxnNew[Any, Unit] =
      new Cas[A](r, ov, nv)

    private[choam] def delay[A, B](uf: A => B): RxnNew[A, B] =
      lift(uf)
  }

  // Representation:

  private final class Commit[A]()
    extends RxnNew[A, A] { private[choam] def tag = 0 }

  private final class Lift[A, B](val func: A => B)
    extends RxnNew[A, B] { private[choam] def tag = 3 }

  private final class Choice[A, B](val first: RxnNew[A, B], val second: RxnNew[A, B])
    extends RxnNew[A, B] { private[choam] def tag = 6 }

  private final class Cas[A](val ref: Ref[A], val ov: A, val nv: A)
    extends RxnNew[Any, Unit] { private[choam] def tag = 7 }

  private final class AndThen[A, B, C](val first: RxnNew[A, B], val second: RxnNew[B, C])
    extends RxnNew[A, C] { private[choam] def tag = 11 }

  // Interpreter:

  private[this] final object ForSome {
    type x
    type y
    type z
  }

  private[this] def newStack[A]() = {
    new ObjStack[A](initSize = 8)
  }

  private[choam] def interpreter[X, R](
    rxn: RxnNew[X, R],
    x: X,
    ctx: ThreadContext,
    maxBackoff: Int = 16,
    randomizeBackoff: Boolean = true
  ): R = {

    val kcas = ctx.impl

    var desc: EMCASDescriptor = kcas.start(ctx)

    val altSnap = newStack[EMCASDescriptor]()
    val altA = newStack[ForSome.x]()
    val altK = newStack[RxnNew[ForSome.x, R]]()
    val altConts = newStack[Array[RxnNew[ForSome.x, R]]]()

    val contK = newStack[RxnNew[ForSome.x, R]]()
    val commit = new Commit[R].asInstanceOf[RxnNew[ForSome.x, R]]
    contK.push(commit)

    var a: Any = x
    var retries: Int = 0

    def saveAlt(k: RxnNew[ForSome.x, R]): Unit = {
      altSnap.push(ctx.impl.snapshot(desc, ctx))
      altA.push(a.asInstanceOf[ForSome.x])
      altK.push(k)
      altConts.push(contK.toArray())
    }

    def next(): RxnNew[ForSome.x, R] = {
      contK.pop()
    }

    def retry(): RxnNew[ForSome.x, R] = {
      retries += 1
      if (altSnap.nonEmpty) {
        desc = altSnap.pop()
        a = altA.pop()
        contK.replaceWith(altConts.pop())
        altK.pop()
      } else {
        desc = kcas.start(ctx)
        a = x
        contK.clear()
        contK.push(commit)
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
          sys.error("TODO") // TODO
        case 5 => // DelayComputed
          sys.error("TODO") // TODO
        case 6 => // Choice
          val c = curr.asInstanceOf[Choice[A, B]]
          saveAlt(c.second.asInstanceOf[RxnNew[ForSome.x, R]])
          loop(c.first)
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
          sys.error("TODO") // TODO
        case 9 => // GenRead
          sys.error("TODO") // TODO
        case 10 => // GenExchange
          sys.error("TODO") // TODO
        case 11 => // AndThen
          val c = curr.asInstanceOf[AndThen[A, ForSome.x, B]]
          contK.push(c.second.asInstanceOf[RxnNew[ForSome.x, R]])
          loop(c.first)
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
