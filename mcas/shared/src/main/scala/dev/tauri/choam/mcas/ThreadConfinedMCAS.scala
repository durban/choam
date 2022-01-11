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
    MemoryLocation.unsafe(Version.Start)

  private[this] val _ctx = new MCAS.ThreadContext {

    final override def readIfValid[A](ref: MemoryLocation[A], validTs: Long): A =
      ref.unsafeGetPlain()

    final override def tryPerform(desc: HalfEMCASDescriptor): Boolean = {
      @tailrec
      def prepare(it: Iterator[HalfWordDescriptor[_]]): Boolean = {
        if (it.hasNext) {
          it.next() match {
            case wd: HalfWordDescriptor[a] =>
              if (equ[a](wd.address.unsafeGetPlain(), wd.ov)) {
                prepare(it)
              } else {
                false
              }
          }
        } else {
          true
        }
      }

      @tailrec
      def execute(it: Iterator[HalfWordDescriptor[_]]): Unit = {
        if (it.hasNext) {
          it.next() match {
            case wd: HalfWordDescriptor[a] =>
              val old = wd.address.unsafeGetPlain()
              assert(equ(old, wd.ov))
              wd.address.unsafeSetPlain(wd.nv)
              execute(it)
          }
        }
      }

      if (prepare(desc.map.valuesIterator)) {
        execute(desc.map.valuesIterator)
        true
      } else {
        false
      }
    }

    final override def readCommitTs(): Long =
      _commitTs.unsafeGetPlain()

    // NB: it is a `def`, not a `val`
    final override private[choam] def random: ThreadLocalRandom =
      ThreadLocalRandom.current()
  }
}
