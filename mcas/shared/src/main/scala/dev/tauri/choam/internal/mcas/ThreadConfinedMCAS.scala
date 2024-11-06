/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package internal
package mcas

import java.util.concurrent.ThreadLocalRandom

private object ThreadConfinedMCAS extends ThreadConfinedMCASPlatform { self =>

  final override def currentContext(): Mcas.ThreadContext =
    _ctx

  private[this] val _ctx = new Mcas.UnsealedThreadContext {

    final override type START = Descriptor

    final override def impl: Mcas =
      self

    final override def readDirect[A](ref: MemoryLocation[A]): A = {
      ref.unsafeGetP()
    }

    final override def readIntoHwd[A](ref: MemoryLocation[A]): LogEntry[A] = {
      val v = ref.unsafeGetP()
      LogEntry(ref, ov = v, nv = v, version = ref.unsafeGetVersionV())
    }

    protected[choam] final override def readVersion[A](ref: MemoryLocation[A]): Long =
      ref.unsafeGetVersionV()

    final override def tryPerformInternal(desc: AbstractDescriptor, optimism: Long): Long = {
      @tailrec
      def prepare(it: Iterator[LogEntry[_]]): Long = {
        if (it.hasNext) {
          it.next() match {
            case wd: LogEntry[a] =>
              val witness = wd.address.unsafeGetP()
              val witnessVer = wd.address.unsafeGetVersionV()
              val isGlobalVerCas = (wd.address eq _commitTs)
              if ((equ[a](witness, wd.ov)) && (isGlobalVerCas || (witnessVer == wd.version))) {
                prepare(it)
              } else {
                if (isGlobalVerCas) witness.asInstanceOf[Long]
                else McasStatus.FailedVal
              }
          }
        } else {
          McasStatus.Successful
        }
      }

      @tailrec
      def execute(it: Iterator[LogEntry[_]], newVersion: Long): Unit = {
        if (it.hasNext) {
          it.next() match {
            case wd: LogEntry[a] =>
              val old = wd.address.unsafeGetP()
              assert(equ(old, wd.ov))
              wd.address.unsafeSetP(wd.nv)
              val ov = wd.address.unsafeGetVersionV()
              val wit = wd.address.unsafeCmpxchgVersionV(ov, newVersion)
              assert(wit == ov)
              wd.address.unsafeNotifyListeners()
              execute(it, newVersion)
          }
        }
      }

      val prepResult = prepare(desc.hwdIterator)
      if (prepResult == McasStatus.Successful) {
        execute(desc.hwdIterator, desc.newVersion)
        McasStatus.Successful
      } else {
        prepResult
      }
    }

    final override def start(): Descriptor =
      Descriptor.empty(_commitTs, this)

    protected[mcas] final override def addVersionCas(desc: AbstractDescriptor): AbstractDescriptor.Aux[desc.D] =
      desc.addVersionCas(_commitTs)

    def validateAndTryExtend(
      desc: AbstractDescriptor,
      hwd: LogEntry[_],
    ): AbstractDescriptor.Aux[desc.D] =
      desc.validateAndTryExtend(_commitTs, this, additionalHwd = hwd)

    // NB: it is a `def`, not a `val`
    final override def random: ThreadLocalRandom =
      ThreadLocalRandom.current()

    final override val refIdGen =
      RefIdGen.global.newThreadLocal()
  }

  private[this] val _commitTs: MemoryLocation[Long] =
    MemoryLocation.unsafeUnpadded(Version.Start, _ctx.refIdGen)
}
