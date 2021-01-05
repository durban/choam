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
package bench
package util

import org.openjdk.jmh.annotations.{ State, Param, Setup, Scope }

import kcas._

@State(Scope.Thread)
class RandomState {

  private[this] val rnd =
    XorShift()

  def nextInt(): Int =
    rnd.nextInt()

  def nextLong(): Long =
    rnd.nextLong()

  def nextString(): String =
    rnd.nextLong().abs.toString
}

@State(Scope.Thread)
class KCASImplState extends RandomState {

  @Param(Array(KCAS.fqns.NaiveKCAS, KCAS.fqns.EMCAS))
  private[choam] var kcasName: String = _

  private[choam] var kcasImpl: KCAS = _

  private[choam] var kcasCtx: ThreadContext = _

  @Setup
  def setupKCASImpl(): Unit = {
    this.kcasImpl = KCAS.unsafeLookup(kcasName)
    this.kcasCtx = this.kcasImpl.currentContext()
  }
}
