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
package kcas

import scala.collection.concurrent.TrieMap

import mcas.MemoryLocation

object FlakyEMCAS extends KCAS {

    private[this] val global =
      new GlobalContext(this)

    private[this] val seen =
      new TrieMap[Int, Unit]

    private[choam] def currentContext(): ThreadContext =
      this.global.threadContext()

    private[choam] def start(ctx: ThreadContext): EMCASDescriptor =
      EMCAS.start(ctx)

    private[choam] def addCas[A](desc: EMCASDescriptor, ref: MemoryLocation[A], ov: A, nv: A, ctx: ThreadContext): EMCASDescriptor =
      EMCAS.addCas(desc, ref, ov, nv, ctx)

    private[choam] def snapshot(desc: EMCASDescriptor, ctx: ThreadContext): EMCASDescriptor =
      EMCAS.snapshot(desc, ctx)

    private[choam] def read[A](ref: MemoryLocation[A], ctx: ThreadContext): A =
      EMCAS.read(ref, ctx)

    private[choam] def tryPerform(desc: EMCASDescriptor, ctx: ThreadContext): Boolean = {
      // sanity check: try to sort (to throw an exception if impossible)
      locally {
        val copy = new java.util.ArrayList[WordDescriptor[_]]
        val it = desc.wordIterator()
        while (it.hasNext()) {
          copy.add(it.next())
        }
        copy.sort(WordDescriptor.comparator) // throws if impossible
      }
      // perform or not the operation based on whether we've already seen it
      var hash = 0x75f4d07d
      val it = desc.wordIterator()
      while (it.hasNext()) {
        hash ^= it.next().address.##
      }
      if (this.seen.putIfAbsent(hash, ()).isDefined) {
        EMCAS.tryPerform(desc, ctx)
      } else {
        false // simulate a transient CAS failure to force a retry
      }
    }
  }

