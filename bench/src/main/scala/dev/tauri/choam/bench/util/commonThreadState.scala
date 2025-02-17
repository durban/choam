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
package bench
package util

import java.lang.Integer.remainderUnsigned

import org.openjdk.jmh.annotations.{ State, Setup, Scope }

import cats.effect.{ IO, SyncIO }

import async.AsyncReactive
import internal.mcas.Mcas

@State(Scope.Thread)
class RandomState {

  private[this] val rnd =
    this.mkXorShift()

  protected def mkXorShift(): XorShift =
    XorShift()

  final def nextInt(): Int =
    rnd.nextInt()

  final def nextIntBounded(bound: Int): Int =
    remainderUnsigned(rnd.nextInt(), bound)

  final def nextLong(): Long =
    rnd.nextLong()

  final def nextString(): String =
    rnd.nextLong().abs.toString

  final def nextBoolean(): Boolean =
    (this.nextInt() % 2) == 0

  final def nextBooleanIO: IO[Boolean] =
    IO { this.nextBoolean() }

  final def nextBooleanZIO: zio.Task[Boolean] =
    zio.ZIO.attempt { this.nextBoolean() }
}

@State(Scope.Thread)
class McasImplState extends McasImplStateBase {

  private[choam] var mcasCtx: Mcas.ThreadContext =
    _

  @Setup
  def setupMcasImpl(): Unit = {
    this.mcasCtx = this.mcasImpl.currentContext()
    java.lang.invoke.VarHandle.releaseFence()
  }
}

abstract class McasImplStateBase {

  private[choam] val reactive: Reactive[IO] =
    McasImplStateBase._reactive

  private[choam] val mcasImpl: Mcas =
    McasImplStateBase._mcasImpl
}

private object McasImplStateBase {

  private val _reactive: Reactive[IO] = {
    AsyncReactive.forAsyncResIn[SyncIO, IO].allocated.unsafeRunSync()._1
  }

  private val _mcasImpl: Mcas = {
    this._reactive.mcasImpl
  }
}
