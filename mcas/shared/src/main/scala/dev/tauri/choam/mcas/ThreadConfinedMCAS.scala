/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

import java.util.concurrent.ThreadLocalRandom

private object ThreadConfinedMCAS extends ThreadConfinedMCASPlatform {

  final override def currentContext(): MCAS.ThreadContext =
    _ctx

  private[this] val _commitTs: MemoryLocation[Long] =
    MemoryLocation.unsafeUnpadded(Version.Start)

  private[this] val _ctx = new MCAS.ThreadContext {

    final override def readDirect[A](ref: MemoryLocation[A]): A = {
      ref.unsafeGetPlain()
    }

    final override def readIntoHwd[A](ref: MemoryLocation[A]): HalfWordDescriptor[A] = {
      val v = ref.unsafeGetPlain()
      HalfWordDescriptor(ref, ov = v, nv = v, version = ref.unsafeGetVersionVolatile())
    }

    protected[mcas] final override def readVersion[A](ref: MemoryLocation[A]): Long =
      ref.unsafeGetVersionVolatile()

    final override def tryPerformInternal(desc: HalfEMCASDescriptor): Long = {
      @tailrec
      def prepare(it: Iterator[HalfWordDescriptor[_]]): Long = {
        if (it.hasNext) {
          it.next() match {
            case wd: HalfWordDescriptor[a] =>
              val witness = wd.address.unsafeGetPlain()
              if (equ[a](witness, wd.ov)) {
                prepare(it)
              } else {
                if (wd.address eq _commitTs) {
                  witness.asInstanceOf[Long]
                } else {
                  EmcasStatus.FailedVal
                }
              }
          }
        } else {
          EmcasStatus.Successful
        }
      }

      @tailrec
      def execute(it: Iterator[HalfWordDescriptor[_]], newVersion: Long): Unit = {
        if (it.hasNext) {
          it.next() match {
            case wd: HalfWordDescriptor[a] =>
              val old = wd.address.unsafeGetPlain()
              assert(equ(old, wd.ov))
              wd.address.unsafeSetPlain(wd.nv)
              assert(wd.address.unsafeCasVersionVolatile(
                wd.address.unsafeGetVersionVolatile(),
                newVersion,
              ))
              execute(it, newVersion)
          }
        }
      }

      val prepResult = prepare(desc.iterator())
      if (prepResult == EmcasStatus.Successful) {
        execute(desc.iterator(), desc.newVersion)
        EmcasStatus.Successful
      } else {
        prepResult
      }
    }

    final override def start(): HalfEMCASDescriptor =
      HalfEMCASDescriptor.empty(_commitTs, this)

    protected[mcas] final override def addVersionCas(desc: HalfEMCASDescriptor): HalfEMCASDescriptor =
      desc.addVersionCas(_commitTs)

    protected[choam] def validateAndTryExtend(desc: HalfEMCASDescriptor): HalfEMCASDescriptor =
      desc.validateAndTryExtend(_commitTs, this)

    // NB: it is a `def`, not a `val`
    final override private[choam] def random: ThreadLocalRandom =
      ThreadLocalRandom.current()
  }
}
