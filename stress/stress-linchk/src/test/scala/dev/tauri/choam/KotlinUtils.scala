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

import java.util.concurrent.ArrayBlockingQueue

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.ExecutionContext

import cats.effect.kernel.{ Async, Sync, Fiber, Poll, Deferred, Ref, Cont }

import kotlin.ResultKt
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.CoroutineSingletons
import kotlin.coroutines.jvm.internal.ContinuationImpl
import kotlinx.coroutines.{
  Deferred => KDeferred,
  BuildersKt,
  CoroutineStart,
  GlobalScope,
  CancellableContinuationKt,
  Dispatchers,
  YieldKt,
  DelayKt,
}

/**
 * Half-correct utilities for working with Kotlin coroutines
 *
 * Lincheck doesn't support blocking operations. To test STM
 * `Txn`s (at least interesting ones), we do need a way to
 * suspend. Thankfully Lincheck does support Kotlin `suspend`
 * functions (coroutines), which can suspend. So, to make this
 * work, we'd need a way to run a `Txn` on a Kotlin coroutine,
 * and the `Txn` suspending should mean a coroutine suspension.
 *
 * To do this, we half-implement a CE `Async` instance for
 * Kotlin suspend functions (see below the abomination that is
 * `ceAsyncForKotlinCoroutine`). Using that, we can get a
 * `Transactive` instance for suspend functions. And using
 * that, we can run our Lincheck tests for `Txn`.
 *
 * This bridge to Kotlin coroutines is very incomplete and
 * incorrect in places. We only implement the parts that we
 * need for our `Txn` tests to run. Notably, we don't care
 * about cancellation at all, since our tests (for now) don't
 * cancel. (We also specify `cancellableOnSuspension = false`
 * for our `@Operation`s, so Lincheck also doesn't cancel them.)
 */
object KotlinUtils {

  /** The type representing zero-argument suspend functions */
  type KtCrt[a] = Function1[Continuation[_ >: a], AnyRef]

  /** Starts the suspend function `crt` on a coroutine */
  final def fork[A](crt: KtCrt[A]): KDeferred[A] = {
    BuildersKt.async[A](
      GlobalScope.INSTANCE, // top-level coroutine, no structured concurrency
      Dispatchers.getDefault(), // start it on the default global threadpool
      CoroutineStart.DEFAULT, // schedule it immediately
      { (_, k) => crt(k) }
    )
  }

  /** Starts `crt` on a coroutine, then blocks the current thread waiting on it */
  final def runSync[A](crt: KtCrt[A]): A = {
    val kd: KDeferred[A] = this.fork(crt)
    val abq = new ArrayBlockingQueue[Unit](1)
    val h = kd.invokeOnCompletion { _ =>
      val ok = abq.offer(())
      _assert(ok)
      kotlin.Unit.INSTANCE
    }
    try {
      abq.take()
    } catch {
      case ex: InterruptedException =>
        h.dispose()
        throw ex
    }
    kd.getCompleted()
  }

  /** Runs `crt` on the current thread, throws if it suspends */
  final def runCompletelyOrThrow[A](crt: KtCrt[A]): A = {
    val r = crt.apply(null)
    if (equ(r, CoroutineSingletons.COROUTINE_SUSPENDED)) {
      throw new IllegalStateException("got COROUTINE_SUSPENDED in runCompletelyOrThrow")
    } else {
      r.asInstanceOf[A]
    }
  }

  /** A partial and incorrect `Async` instance for suspend functions */
  final def ceAsyncForKotlinCoroutine: Async[KtCrt] = {
    new Async[KtCrt] {

      // we don't care about cancellation for now, so this is our Poll:
      private[this] val nopPoll: Poll[KtCrt] = new Poll[KtCrt] {
        final override def apply[A](fa: KtCrt[A]): KtCrt[A] = fa
      }

      private[this] def scalaUnitFromKotlinUnit(k: Continuation[_ >: Unit]): Continuation[_ >: kotlin.Unit] = {
        new ContinuationImpl(k.asInstanceOf[Continuation[AnyRef]]) {
          final override def invokeSuspend(x: AnyRef): AnyRef = {
            ResultKt.throwOnFailure(x)
            // we're converting a Kotlin unit to a Scala unit:
            _assert(x eq kotlin.Unit.INSTANCE)
            scala.runtime.BoxedUnit.UNIT
          }
        }
      }

      // we need these to run our tests:

      final override def pure[A](x: A): KtCrt[A] = { _ =>
        val res: AnyRef = box(x)
        _assert(res ne CoroutineSingletons.COROUTINE_SUSPENDED)
        res
      }

      final override def raiseError[A](e: Throwable): KtCrt[A] = { _ =>
        throw e
      }

      final override def flatMap[A, B](fa: KtCrt[A])(f: A => KtCrt[B]): KtCrt[B] = { k =>
        KotlinUtils.flatMapImpl[A, B](fa)(f)(k, first = true)
      }

      final override def uncancelable[A](body: Poll[KtCrt] => KtCrt[A]): KtCrt[A] = {
        body(nopPoll) // this is incorrect (not really uncancelable), but fine for our tests
      }

      final override def suspend[A](hint: Sync.Type)(thunk: => A): KtCrt[A] = { _ =>
        // we're ignoring `hint`; it doesn't matter for our tests
        val res: AnyRef = box(thunk)
        _assert(res ne CoroutineSingletons.COROUTINE_SUSPENDED)
        res
      }

      final override def cede: KtCrt[Unit] = { k =>
        YieldKt.`yield`(scalaUnitFromKotlinUnit(k))
      }

      final override def sleep(time: FiniteDuration): KtCrt[Unit] = {
        val millis = time.toMillis
        (k) => { DelayKt.delay(millis, scalaUnitFromKotlinUnit(k)) }
      }

      final override def cont[K, R](body: Cont[KtCrt, K, R]): KtCrt[R] = {
        Async.defaultCont(body)(this)
      }

      final override def async[A](k: (Either[Throwable, A] => Unit) => KtCrt[Option[KtCrt[Unit]]]): KtCrt[A] = { kk =>
        val res: AnyRef = CancellableContinuationKt.suspendCancellableCoroutine[A]({ c =>
          val crt: KtCrt[Option[KtCrt[Unit]]] = k({
            case Left(ex) =>
              c.resumeWith(ResultKt.createFailure(ex))
            case Right(a) =>
              _assert(!equ(a, CoroutineSingletons.COROUTINE_SUSPENDED))
              c.resumeWith(a)
          })
          val x: AnyRef = runCompletelyOrThrow(crt) // TODO: we're cheating here
          val fin = x.asInstanceOf[Option[KtCrt[Unit]]]
          fin match {
            case None =>
              // technically should be uncancelable, but it doesn't matter for our tests
            case Some(fin) =>
              c.invokeOnCancellation({ _ =>
                runCompletelyOrThrow(fin) // TODO: cheating again
                kotlin.Unit.INSTANCE
              })
          }
          kotlin.Unit.INSTANCE
        }, kk)
        res
      }

      // technically we don't really need these:

      final override def ref[A](a: A): KtCrt[Ref[KtCrt, A]] = delay {
        Ref.unsafe[KtCrt, A](a)(using this)
      }

      final override def deferred[A]: KtCrt[Deferred[KtCrt, A]] = delay {
        new Deferred.AsyncDeferred[KtCrt, A]()(using this)
      }

      // we're not implementing these, we don't need them for our tests:

      final override def handleErrorWith[A](fa: KtCrt[A])(f: Throwable => KtCrt[A]): KtCrt[A] =
        sys.error("not implemented")

      final override def tailRecM[A, B](a: A)(f: A => KtCrt[Either[A, B]]): KtCrt[B] =
        sys.error("not implemented")

      final override def forceR[A, B](fa: KtCrt[A])(fb: KtCrt[B]): KtCrt[B] =
        sys.error("not implemented")

      final override def canceled: KtCrt[Unit] =
        sys.error("not implemented")

      final override def onCancel[A](fa: KtCrt[A], fin: KtCrt[Unit]): KtCrt[A] =
        sys.error("not implemented")

      final override def monotonic: KtCrt[FiniteDuration] =
        sys.error("not implemented")

      final override def realTime: KtCrt[FiniteDuration] =
        sys.error("not implemented")

      final override def start[A](fa: KtCrt[A]): KtCrt[Fiber[KtCrt, Throwable, A]] =
        sys.error("not implemented")

      final override def evalOn[A](fa: KtCrt[A], ec: ExecutionContext): KtCrt[A] =
        sys.error("not implemented")

      final override def executionContext: KtCrt[ExecutionContext] =
        sys.error("not implemented")
    }
  }

  private[this] final class FlatMapCont(val cont: Continuation[_ >: AnyRef], val f: AnyRef => KtCrt[AnyRef])
    extends ContinuationImpl(cont) {

    var state: Int = 0

    var result: AnyRef = null

    final override def invokeSuspend(x: AnyRef): AnyRef = {
      this.result = x
      flatMapImpl(null)(f)(this, first = false)
    }
  }

  /**
   * This is approximately what the Kotlin compiler would
   * generate from a suspend function like this:
   *
   * ```
   * suspend fun foo(): B {
   *   val a = fa()
   *   return f(a)
   * }
   * ```
   *
   * Given:
   *
   * ```
   * suspend fun fa(): A { ... }
   * suspend fun f(a: A): B { ... }
   * ```
   *
   * (I.e., `foo` above is approximately `fa.flatMap(f)`.)
   */
  private final def flatMapImpl[A, B](fa: KtCrt[A])(f: A => KtCrt[B])(
    k: Continuation[_ >: B],
    first: Boolean,
  ): AnyRef = {
    if (k eq null) {
      // not a real coroutine, probably runCompletelyOrThrow
      _assert(first)
      val x = fa.apply(null)
      if (x eq CoroutineSingletons.COROUTINE_SUSPENDED) {
        throw new IllegalStateException("null continuation, but got COROUTINE_SUSPENDED from fa")
      } else {
        val fb = f(x.asInstanceOf[A])
        val y = fb.apply(null)
        if (y eq CoroutineSingletons.COROUTINE_SUSPENDED) {
          throw new IllegalStateException("null continuation, but got COROUTINE_SUSPENDED from f(a)")
        } else {
          y
        }
      }
    } else {
      val kk = if (first) {
        new FlatMapCont(k.asInstanceOf[Continuation[AnyRef]], f.asInstanceOf[AnyRef => KtCrt[AnyRef]])
      } else {
        k.asInstanceOf[FlatMapCont]
      }
      kk.state match {
        case 0 =>
          ResultKt.throwOnFailure(kk.result)
          kk.state = 1
          val x = fa.apply(kk.asInstanceOf[Continuation[A]])
          if (x eq CoroutineSingletons.COROUTINE_SUSPENDED) {
            x
          } else {
            kk.result = x
            flatMapImpl(fa)(f)(kk.asInstanceOf[Continuation[B]], first = false)
          }
        case 1 =>
          ResultKt.throwOnFailure(kk.result)
          val a: A = kk.result.asInstanceOf[A]
          kk.state = 2
          val x = f(a).apply(kk.asInstanceOf[Continuation[B]])
          if (x eq CoroutineSingletons.COROUTINE_SUSPENDED) {
            x
          } else {
            kk.result = x
            flatMapImpl(fa)(f)(kk.asInstanceOf[Continuation[B]], first = false)
          }
        case 2 =>
          ResultKt.throwOnFailure(kk.result)
          val b: B = kk.result.asInstanceOf[B]
          box(b)
        case _ =>
          throw new IllegalStateException
      }
    }
  }
}
