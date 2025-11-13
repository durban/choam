/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicReference

import cats.effect.kernel.{ Sync, Resource }

import internal.mcas.{ Mcas, OsRng }

sealed trait ChoamRuntime {

  private[choam] def mcasImpl: Mcas

  private[choam] def unsafeCloseBlocking(): Unit
}

object ChoamRuntime {

  private[this] final class ChoamRuntimeImpl private[ChoamRuntime] (
    private[choam] final override val mcasImpl: Mcas,
    osRng: OsRng,
  ) extends ChoamRuntime {

    private[choam] final override def unsafeCloseBlocking(): Unit = {
      closeRt(this)
    }

    private[ChoamRuntime] final def closeInternal(): Unit = {
      this.mcasImpl.close()
      this.osRng.close()
    }
  }

  final def make[F[_]](implicit F: Sync[F]): Resource[F, ChoamRuntime] = {
    Resource.make(F.blocking { this.unsafeBlocking() }) { rt =>
      F.blocking { rt.unsafeCloseBlocking() }
    }
  }

  /** Acquires resources, allocates a new runtime; may block! */
  private[choam] final def unsafeBlocking(): ChoamRuntime =
    this.getRt()

  private[this] final def constructNew(): ChoamRuntimeImpl = {
    val numCpu = java.lang.Runtime.getRuntime().availableProcessors()
    val o = OsRng.mkNew() // may block due to /dev/random
    val m = Mcas.newDefaultMcas(o, numCpu = numCpu) // may block due to JMX
    new ChoamRuntimeImpl(m, o)
  }

  private[this] final def constructFromOld(old: ChoamRuntimeImpl): ChoamRuntimeImpl = {
    val o = OsRng.mkNew() // no state to copy
    val m = old.mcasImpl.makeCopy(o)
    new ChoamRuntimeImpl(m, o)
  }

  /** Only for testing! */
  private[choam] final def forTesting(mcasImpl: Mcas): ChoamRuntime = {
    new ChoamRuntimeImpl(mcasImpl, mcasImpl.osRng)
  }

  private[this] sealed abstract class State

  private[this] final class UninitOrClosed(val ov: Option[ChoamRuntimeImpl]) extends State {

    final override def toString: String = ov match {
      case None => "Uninitialized"
      case Some(rt) => s"Closed(${rt})"
    }
  }

  private[this] final class InUse(val rt: ChoamRuntimeImpl, val refCnt: Long) extends State {

    _assert(refCnt > 0L)

    final override def toString: String =
      s"InUse(${rt}, ${refCnt})"
  }

  private[this] final val holder: AtomicReference[State] =
    new AtomicReference(new UninitOrClosed(None))

  @tailrec
  private[this] final def getRt(): ChoamRuntime = {
    holder.get() match {
      case ov: UninitOrClosed =>
        val newRt = initializeNew(ov.ov)
        val nv = new InUse(newRt, refCnt = 1L)
        if (holder.compareAndSet(ov, nv)) {
          newRt // we're done
        } else {
          newRt.closeInternal()
          getRt()
        }
      case ov: InUse =>
        val rt = ov.rt
        val oldRefCnt = ov.refCnt
        val newRefCnt = oldRefCnt + 1L
        Predef.assert(newRefCnt > ov.refCnt) // detect overflow (unlikely)
        val nv = new InUse(rt, newRefCnt)
        if (holder.compareAndSet(ov, nv)) {
          rt
        } else {
          getRt()
        }
    }
  }

  private[this] final def initializeNew(oldRt: Option[ChoamRuntimeImpl]): ChoamRuntimeImpl = {
    oldRt match {
      case None =>
        this.constructNew()
      case Some(old) =>
        this.constructFromOld(old)
    }
  }

  @tailrec
  private[this] final def closeRt(rt: ChoamRuntime): Unit = {
    holder.get() match {
      case ov: UninitOrClosed =>
        impossible(s"closeRt found ${ov}")
      case ov: InUse =>
        val refCnt = ov.refCnt
        _assert((ov.rt eq rt) && (refCnt > 0L))
        if (refCnt > 1L) {
          val nv = new InUse(ov.rt, refCnt - 1L)
          if (!holder.compareAndSet(ov, nv)) {
            closeRt(rt)
          }
        } else { // 1
          val oldRt = ov.rt
          val nv = new UninitOrClosed(Some(oldRt))
          if (holder.compareAndSet(ov, nv)) {
            // OK, we've closed it logically, but
            // we have to actually release resources:
            oldRt.closeInternal()
          } else {
            closeRt(rt)
          }
        }
    }
  }
}
