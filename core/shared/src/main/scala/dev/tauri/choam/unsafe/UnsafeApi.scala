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
package unsafe

import cats.~>
import cats.effect.kernel.Async

import core.{ Rxn, RetryStrategy }
import internal.mcas.Mcas

object UnsafeApi {

  final def apply(rt: ChoamRuntime): UnsafeApi =
    new UnsafeApi(rt) {}
}

sealed abstract class UnsafeApi private (rt: ChoamRuntime) {

  // `atomically`: running in fully synchronous mode

  /**
   * Note: don't nest calls to `atomically`!
   *
   * Also: don't call `atomicallyInAsync` inside
   * `atomically` (or the other way around)!
   *
   * Instead pass the `InRxn` argument implicitly
   * to methods called from the `block`.
   */
  final def atomically[A](block: InRxn => A): A = {
    val state = Rxn.unsafe.startImperative(this.rt.mcasImpl, RetryStrategy.Default : RetryStrategy.Spin)
    state.initCtx(this.rt.mcasImpl.currentContext())

    @tailrec
    def go(): A = {
      val result = this.runBlock(state, block)
      if (state.imperativeCommit()) {
        state.beforeResult()
        result
      } else {
        this.imperativeRetry(state)
        go()
      }
    }

    val a = go()
    // TODO: saveStats
    state.invalidateCtx()
    a
  }

  /** Like `atomically`, but only read-only operations are allowed */
  final def atomicallyReadOnly[A](block: InRoRxn => A): A = {
    this.atomically(block) // TODO: optimize for read-only execution
  }

  private[this] final def runBlock[A](state: InRxn, block: InRxn => A): A = {
    var done = false
    var result: A = nullOf[A]
    while (!done) {
      try {
        result = block(state)
        done = true
      } catch {
        case _: RetryException =>
          this.imperativeRetry(state)
      }
    }
    result
  }

  private[this] final def imperativeRetry(state: InRxn): Unit = {
    val opt: Option[CanSuspendInF] = state.imperativeRetry()
    _assert(opt.isEmpty)
  }

  // `atomicallyInAsync`: possibly async retries

  // TODO: Instead/besides `atomicallyInAsync`, we could have a
  // TODO: coroutine-like API. But which coroutine impl to use?

  /**
   * Note: don't nest calls to `atomicallyInAsync`!
   *
   * Also: don't call `atomically` inside
   * `atomicallyInAsync` (or the other way around)!
   *
   * Instead pass the `InRxn` argument implicitly
   * to methods called from the `block`.
   */
  final def atomicallyInAsync[F[_], A](str: RetryStrategy)(block: InRxn => A)(implicit F: Async[F]): F[A] = {
    F.uncancelable { poll =>
      F.defer {
        val state = Rxn.unsafe.startImperative(this.rt.mcasImpl, str)
        this.runAsync[F, A](state, block, str, poll)
      }
    }
  }

  /** Like `atomicallyInAsync`, but only read-only operations are allowed */
  final def atomicallyReadOnlyInAsync[F[_], A](str: RetryStrategy)(block: InRoRxn => A)(implicit F: Async[F]): F[A] = {
    this.atomicallyInAsync(str)(block)(using F) // TODO: optimize for read-only execution
  }

  private[this] final def runAsync[F[_], A](
    state: InRxn,
    block: InRxn => A,
    str: RetryStrategy,
    poll: F ~> F,
  )(implicit F: Async[F]): F[A] = {
    if (str.canSuspend) {
      // cede or sleep strategy:
      val mcas = this.rt.mcasImpl
      def step(ctxHint: Mcas.ThreadContext): F[A] = F.defer {
        val ctx = if ((ctxHint ne null) && mcas.isCurrentContext(ctxHint)) {
          ctxHint
        } else {
          mcas.currentContext()
        }
        state.initCtx(ctx)
        try {
          try {
            val result = block(state)
            if (state.imperativeCommit()) {
              state.beforeResult()
              F.pure(result)
            } else {
              step(ctx)
            }
          } catch {
            case _: RetryException =>
              state.imperativeRetry() match {
                case None =>
                  // spinning done, retry immediately:
                  step(ctx)
                case Some(canSuspend) =>
                  // we'll suspend:
                  state.beforeSuspend()
                  val sus = canSuspend.suspend[F](mcas, ctx)
                  F.flatMap(poll(sus)) { _ => step(ctxHint = ctx) }
              }
          }
        } finally {
          // TODO: this.saveStats()
          state.invalidateCtx()
        }
      }
      step(ctxHint = null)
    } else {
      // spin strategy, so not really async:
      F.delay {
        this.atomically(block)
      }
    }
  }
}
