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

import java.util.concurrent.atomic.AtomicLong

import IBRStackFast.{ Node, Cons, TC }

/**
 * Treiber stack, which uses IBR for nodes (debug version)
 *
 * It's "debug", because it has extra assertions.
 * It's used for testing IBR correctness.
 */
final class IBRStackDebug[A] private (val debugGc: IBRStackDebug.GC[A])
  extends IBRStackFast[A](debugGc) {

  final override def checkReuse(tc: TC[A], c: IBRStackFast.Cons[A]): Unit = {
    if (c.asInstanceOf[DebugManaged[TC[A], Node[A]]].freed > 0) {
      tc.globalContext.asInstanceOf[IBRStackDebug.GC[A]].reuseCount.getAndIncrement()
      ()
    }
  }
}

final object IBRStackDebug {

  def apply[A](els: A*): IBRStackDebug[A] = {
    val s = new IBRStackDebug[A](gc.asInstanceOf[GC[A]])
    val tc = s.gc.threadContext()
    els.foreach(e => s.push(e, tc))
    s
  }

  private[kcas] def threadLocalContext[A](): TC[A] =
    gc.asInstanceOf[IBR[TC[A], Node[A]]].threadContext()

  private[this] val gc =
    new GC[Any]

  final class GC[A] extends IBRStackFast.GC[A] {

    val reuseCount = new AtomicLong

    final override def allocateNew() = {
      new DebugCons[A]
    }
  }

  final class DebugCons[A] extends Cons[A] with DebugManaged[TC[A], Node[A]] {
    override def allocate(tc: TC[A]): Unit = {
      super.allocate(tc)
      val hd = this.getHeadVh().get(this)
      assert(hd eq null, s"head is '${hd}'")
      assert(equ(tc.readVhAcquire[Node[A]](this.getTailVh(), this), null))
    }
    override def retire(tc: TC[A]): Unit = {
      super[DebugManaged].retire(tc)
      super[Cons].retire(tc)
    }
    override def free(tc: TC[A]): Unit = {
      super[DebugManaged].free(tc)
      super[Cons].free(tc)
    }
    override def getHeadVh() = {
      this.checkAccess()
      super.getHeadVh()
    }
    override def getTailVh() = {
      this.checkAccess()
      super.getTailVh()
    }
  }
}
