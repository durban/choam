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
package internal
package mcas

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicLong

private final class ThreadConfinedMcas(
  private[choam] final override val osRng: OsRng,
) extends ThreadConfinedMcasPlatform { self =>

  final override def currentContext(): Mcas.ThreadContext =
    _ctx

  private[choam] final override def close(): Unit =
    ()

  private[choam] final override def stripes: Int =
    1

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
      def prepare(it: Iterator[LogEntry[?]]): Long = {
        if (it.hasNext) {
          it.next() match {
            case wd: LogEntry[a] =>
              val witness = wd.address.unsafeGetP()
              val witnessVer = wd.address.unsafeGetVersionV()
              if ((equ[a](witness, wd.ov)) && (witnessVer == wd.version)) {
                prepare(it)
              } else {
                McasStatus.FailedVal
              }
          }
        } else {
          McasStatus.Successful
        }
      }

      @tailrec
      def execute(it: Iterator[LogEntry[?]], newVersion: Long): Unit = {
        if (it.hasNext) {
          it.next() match {
            case wd: LogEntry[_] =>
              _assert(equ(wd.address.unsafeGetP(), wd.ov))
              wd.address.unsafeSetP(wd.nv)
              val ov = wd.address.unsafeGetVersionV()
              val wit = wd.address.unsafeCmpxchgVersionV(ov, newVersion)
              _assert(wit == ov)
              wd.address.unsafeNotifyListeners()
              execute(it, newVersion)
          }
        }
      }

      def incrTs(): Long = {
        val ts = _commitTs.incrementAndGet()
        Predef.assert(VersionFunctions.isValid(ts)) // detect version overflow
        ts
      }

      val prepResult = prepare(desc.hwdIterator)
      if (prepResult == McasStatus.Successful) {
        execute(desc.hwdIterator, incrTs())
        McasStatus.Successful
      } else {
        prepResult
      }
    }

    final override def start(): Descriptor =
      Descriptor.emptyFromVer(_commitTs.get())

    def validateAndTryExtend(
      desc: AbstractDescriptor,
      hwd: LogEntry[?],
    ): AbstractDescriptor.Aux[desc.D] =
      desc.validateAndTryExtendVer(_commitTs.get(), this, additionalHwd = hwd)

    // NB: it is a `def`, not a `val`
    final override def random: ThreadLocalRandom =
      ThreadLocalRandom.current()

    final override val refIdGen =
      RefIdGen.newGlobal().newThreadLocal()
  }

  private[this] val _commitTs: AtomicLong =
    new AtomicLong(Version.Start)
}
