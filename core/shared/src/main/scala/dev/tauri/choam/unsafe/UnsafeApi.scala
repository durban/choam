/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
import cats.syntax.all._
import cats.effect.kernel.Async

import core.Rxn
import internal.mcas.Mcas
import Mcas.ThreadContext

object UnsafeApi {

  final def apply(rt: ChoamRuntime): UnsafeApi =
    new UnsafeApi(rt) {}

  private[UnsafeApi] sealed abstract class AttemptRes[+A] {
    final def cast[B]: AttemptRes[B] = this.asInstanceOf[AttemptRes[B]]
  }

  private[UnsafeApi] sealed abstract class SyncRes[+A] extends AttemptRes[A]
  private[UnsafeApi] final class Done[A](val res: A) extends SyncRes[A]
  private[UnsafeApi] final class Alt[A](val alt: Rxn[A]) extends SyncRes[A]
  private[UnsafeApi] final object ImmediateFullRetry extends SyncRes[Nothing]
  private[UnsafeApi] final class Suspend(val sus: CanSuspendInF, val ctx: ThreadContext) extends AttemptRes[Nothing]
}

sealed abstract class UnsafeApi private (rt: ChoamRuntime) {

  import UnsafeApi.{ AttemptRes, SyncRes, Done, Alt, ImmediateFullRetry, Suspend }

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
    this.atomicallyWithAlts(block)
  }

  /** Like `atomically`, but only read-only operations are allowed */
  final def atomicallyReadOnly[A](block: InRoRxn => A): A = {
    this.atomically(block) // TODO: optimize for read-only execution
  }

  private[choam] final def atomicallyWithAlts[A](block: Function1[InRxn, A], alts: Rxn[A]*): A = {
    val state = Rxn.unsafe.startImperative(this.rt.mcasImpl, RetryStrategy.Default : RetryStrategy.CanSuspend[false])
    state.initCtx(this.rt.mcasImpl.currentContext())
    addAlts(state, alts)

    @tailrec
    def go(alt: Option[Rxn[A]]): A = {
      val syncRes = alt match {
        case None => this.runBlockSync(state, block)
        case Some(alt) => this.runAltSync(state, alt)
      }
      syncRes match {
        case newAlt: Alt[_] => go(Some(newAlt.alt.asInstanceOf[Rxn[A]]))
        case done: Done[_] => done.res
        case ImmediateFullRetry => go(None)
      }
    }

    val a = go(None)
    val res = if (state.imperativeCommit()) {
      state.beforeResult()
      a
    } else {
      val maybeAlt: Option[Rxn[A]] = this.imperativeRetryNoSuspend(state) match {
        case Some(newAlt) => Some(newAlt.asInstanceOf[Rxn[A]])
        case None => None
      }
      go(maybeAlt)
    }
    // TODO: saveStats
    state.invalidateCtx()
    res
  }

  private[this] final def addAlts[A](state: InRxn, alts: Seq[Rxn[A]]): Unit = {
    val len = alts.length
    var idx = len - 1
    while (idx >= 0) {
      jsCheckIdx(idx, len)
      state.imperativeAddAlt(alts(idx))
      idx -= 1
    }
  }

  private[this] final def runBlockSync[A](state: InRxn, block: InRxn => A): SyncRes[A] = {
    try {
      new Done(block(state))
    } catch {
      case _: RetryException =>
        this.imperativeRetryNoSuspend(state) match {
          case Some(alt) => new Alt(alt.asInstanceOf[Rxn[A]])
          case None => ImmediateFullRetry
        }
    }
  }

  private[this] final def runAltSync[A](state: InRxn, alt: Rxn[A]): SyncRes[A] = {
    try {
      new Done(state.embedRxn(alt))
    } catch {
      case _: RetryException =>
        this.imperativeRetryNoSuspend(state) match {
          case Some(newAlt) => new Alt(newAlt.asInstanceOf[Rxn[A]])
          case None => ImmediateFullRetry
        }
    }
  }

  private[this] final def runAltAsync[F[_], A](state: InRxn, ctx: ThreadContext, alt: Rxn[A])(implicit F: Async[F]): F[AttemptRes[A]] = {
    F.delay {
      try {
        new Done(state.embedRxn(alt))
      } catch {
        case _: RetryException =>
          imperativeRetryMaySuspend(state, ctx).asInstanceOf[AttemptRes[A]]
      }
    }
  }

  private[this] final def imperativeRetryNoSuspend(state: InRxn): Option[Rxn[?]] = {
    state.imperativeRetry() match {
      case Left(None) =>
        None // ok, full retry without alt
      case Left(Some(suspend)) =>
        impossible(s"imperativeRetryNoSuspend got ${suspend}")
      case Right(alt) =>
        Some(alt)
    }
  }

  private[this] final def imperativeRetryMaySuspend(state: InRxn, ctx: ThreadContext): AttemptRes[?] = {
    state.imperativeRetry() match {
      case Left(None) =>
        ImmediateFullRetry // ok, full retry without alt
      case Left(Some(suspend)) =>
        new Suspend(suspend, ctx)
      case Right(alt) =>
        new Alt(alt)
    }
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
    atomicallyInAsyncWithAlts(str)(block)
  }

  /** Like `atomicallyInAsync`, but only read-only operations are allowed */
  final def atomicallyReadOnlyInAsync[F[_], A](str: RetryStrategy)(block: InRoRxn => A)(implicit F: Async[F]): F[A] = {
    this.atomicallyInAsync(str)(block)(using F) // TODO: optimize for read-only execution
  }

  private[choam] final def atomicallyInAsyncWithAlts[F[_], A](str: RetryStrategy)(block: InRxn => A, alts: Rxn[A]*)(implicit F: Async[F]): F[A] = {
    F.uncancelable { poll =>
      F.defer {
        val state = Rxn.unsafe.startImperative(this.rt.mcasImpl, str)
        addAlts(state, alts)
        this.runAsync[F, A](state, block, str, poll)
      }
    }
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
      def step(ctxHint: ThreadContext): AttemptRes[A] = {
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
              new Done(result)
            } else {
              imperativeRetryMaySuspend(state, ctx).cast[A]
            }
          } catch {
            case _: RetryException =>
              imperativeRetryMaySuspend(state, ctx).cast[A]
          }
        } finally {
          // TODO: this.saveStats()
          state.invalidateCtx()
        }
      }

      def go(res: AttemptRes[A], ctxHint: ThreadContext): F[A] = res match {
        case d: Done[_] =>
          F.pure(d.res)
        case ImmediateFullRetry =>
          go(step(ctxHint), ctxHint)
        case sus: Suspend =>
          state.beforeSuspend()
          val ctx = sus.ctx
          F.flatMap(poll(sus.sus.suspend(mcas, ctx))) { _ => go(step(ctx), ctx) }
        case alt: Alt[?] =>
          runAltAsync(state, ctxHint, alt.alt).flatMap(go(_, ctxHint))
      }

      go(step(null), null)
    } else {
      // spin strategy, so not really async:
      F.delay {
        this.atomically(block)
      }
    }
  }
}
