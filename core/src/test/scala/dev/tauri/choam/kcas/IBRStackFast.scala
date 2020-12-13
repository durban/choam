/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

import scala.annotation.{ tailrec, unused }

import cats.data.Chain

/**
 * Treiber stack, which uses IBR for nodes
 *
 * It's "fast", because it has no debug assertions.
 * It's used for testing IBR correctness and performance.
 */
class IBRStackFast[A](
  val gc: IBR[IBRStackFast.TC[A], IBRStackFast.Node[A]]
) {

  import IBRStackFast.{ Node, Cons, End, TC }

  private[this] val head = {
    // we're abusing a `Cons`, and use its `_tail` as an `AtomicReference`
    val c = new Cons[A]
    c.getTailVh().setVolatile(c, End) : Unit
    c
  }

  private[this] val _sentinel: A =
    (new AnyRef).asInstanceOf[A]

  /** See `IBRStackDebug` */
  protected def checkReuse(@unused tc: TC[A], @unused c: Cons[A]): Unit =
    ()

  final def push(a: A, tc: TC[A]): Unit = {
    @tailrec
    def go(newHead: Cons[A]): Unit = {
      val oldHead = tc.readVhAcquire[Node[A]](this.head.getTailVh, this.head)
      newHead.setTailOpaque(oldHead) // opaque: the CAS below will make it visible
      if (!tc.casVh(this.head.getTailVh, this.head, oldHead, newHead)) {
        go(newHead)
      }
    }
    tc.startOp()
    try {
      val newHead = tc.alloc().asInstanceOf[Cons[A]]
      this.checkReuse(tc, newHead)
      newHead.setHeadOpaque(a) // opaque: the CAS in `go` will make it visible
      go(newHead)
    } finally tc.endOp()
  }

  @tailrec
  final def tryPop(tc: TC[A]): A = {
    tc.startOp()
    val res = try {
      val curr = tc.readVhAcquire[Node[A]](this.head.getTailVh, this.head)
      curr match {
        case c: Cons[_] =>
          val tail = tc.readVhAcquire[Node[A]](c.getTailVh, c)
          if (tc.casVh(this.head.getTailVh, this.head, curr, tail)) {
            val res = tc.readVhAcquire[A](c.getHeadVh(), c)
            tc.retire(c)
            res
          } else {
            _sentinel // retry
          }
        case _ =>
          nullOf[A]
      }
    } finally tc.endOp()
    if (equ(res, _sentinel)) tryPop(tc)
    else res
  }

  final def tryPopN(to: Array[A], n: Int, tc: TC[A]): Int = {
    def go(left: Int): Int = {
      this.tryPop(tc) match {
        case null =>
          left
        case a =>
          to(n - left) = a
          if (left > 1) go(left - 1) else left - 1
      }
    }

    tc.startOp()
    try {
      val left = go(n)
      n - left
    } finally tc.endOp()
  }

  final def pushAll(arr: Array[A], tc: TC[A]): Unit = {
    def go(idx: Int): Unit = {
      if (idx < arr.length) {
        this.push(arr(idx), tc)
        go(idx + 1)
      }
    }

    tc.startOp()
    try go(0)
    finally tc.endOp()
  }

  /** For testing; not threadsafe */
  private[kcas] final def unsafeToList(tc: TC[A]): List[A] = {
    @tailrec
    def go(next: Cons[A], acc: Chain[A]): Chain[A] = {
      tc.readVhAcquire[Node[A]](next.getTailVh, next) match {
        case c: Cons[_] =>
          go(c, acc :+ tc.readVhAcquire[A](c.getHeadVh(), c))
        case _: End.type =>
          acc
      }
    }
    tc.startOp()
    try {
      go(this.head, Chain.empty).toList
    } finally tc.endOp()
  }
}

final object IBRStackFast {

  def apply[A](els: A*): IBRStackFast[A] = {
    val s = new IBRStackFast[A](gc.asInstanceOf[IBR[TC[A], Node[A]]])
    val tc = s.gc.threadContext()
    els.foreach(e => s.push(e, tc))
    s
  }

  private[kcas] def threadLocalContext[A](): TC[A] =
    gc.asInstanceOf[IBR[TC[A], Node[A]]].threadContext()

  private[kcas] val gc =
    new GC[Any]

  class GC[A] extends IBR[TC[A], Node[A]](0L) {
    override def allocateNew(): Node[A] =
      new Cons[A]
    final override def dynamicTest[X](a: X): Boolean =
      a.isInstanceOf[Node[_]]
    override def newThreadContext(): TC[A] =
      new TC(this)
  }

  class TC[A](global: IBR[TC[A], Node[A]])
    extends IBR.ThreadContext[TC[A], Node[A]](global)

  private[kcas] sealed trait Node[A]
    extends IBRManaged[TC[A], Node[A]]

  private[kcas] class Cons[A]
    extends IBRStackFastConsBase[A, TC[A], Node[A]] with Node[A] {

    override def toString(): String = {
      val h: A = this.getHeadVh().getVolatile(this)
      val t: Node[A] = this.getTailVh().getVolatile(this)
      s"Cons(${h}, ${t})"
    }

    override protected[kcas] def allocate(tc: TC[A]): Unit =
      ()

    override protected[kcas] def retire(tc: TC[A]): Unit = {
      this.setHeadOpaque(nullOf[A])
      this.setTailOpaque(nullOf[Node[A]])
    }

    override protected[kcas] def free(tc: TC[A]): Unit =
      ()
  }

  private[kcas] final object End extends Node[Nothing] {
    override protected[kcas] def allocate(tc: TC[Nothing]): Unit =
      ()
    override protected[kcas] def retire(tc: TC[Nothing]): Unit =
      throw new Exception("End should never be retired")
    override protected[kcas] def free(tc: TC[Nothing]): Unit =
      throw new Exception("End should never be freed")
    def widen[A]: Node[A] =
      this.asInstanceOf[Node[A]]
  }
}
