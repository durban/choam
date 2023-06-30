/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.lang.Integer.remainderUnsigned

import org.openjdk.jmh.annotations.{ State, Param, Setup, Scope }

import cats.effect.IO

import async.AsyncReactive
import internal.mcas.Mcas

@State(Scope.Thread)
class RandomState {

  private[this] val rnd =
    this.mkXorShift()

  protected def mkXorShift(): XorShift =
    XorShift()

  def nextInt(): Int =
    rnd.nextInt()

  def nextIntBounded(bound: Int): Int =
    remainderUnsigned(rnd.nextInt(), bound)

  def nextLong(): Long =
    rnd.nextLong()

  def nextString(): String =
    rnd.nextLong().abs.toString

  final def nextBooleanIO: IO[Boolean] =
    IO { (this.nextInt() % 2) == 0 }

  final def nextBooleanZIO: zio.Task[Boolean] =
    zio.ZIO.attempt { (this.nextInt() % 2) == 0 }
}

@State(Scope.Thread)
class McasImplState extends RandomState {

  @Param(Array(Mcas.fqns.Emcas))
  private[choam] var mcasName: String = _

  private[choam] var mcasImpl: Mcas = _

  private[choam] var mcasCtx: Mcas.ThreadContext = _

  private[choam] var reactive: Reactive[IO] = _

  @Setup
  def setupMcasImpl(): Unit = {
    this.mcasImpl = Mcas.unsafeLookup(mcasName)
    this.mcasCtx = this.mcasImpl.currentContext()
    this.reactive = {
      val ar = AsyncReactive.asyncReactiveForAsync[IO](IO.asyncForIO)
      assert(ar.mcasImpl eq this.mcasImpl)
      ar
    }
    java.lang.invoke.VarHandle.releaseFence()
  }
}
