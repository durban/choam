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
package bench
package next

import scala.concurrent.duration.FiniteDuration

import cats.{ effect => ce }

import kyo.{ <, Sync, Async, Abort, Loop }

object KyoUtils {

  /** Like `Kyo.fill`, but doesn't collect results */
  final def repeat[A, S](n: Int)(task: A < S): Unit < S = {
    require(n >= 0)
    Loop.indexed { idx =>
      if (idx == n) Loop.done
      else task `andThen` Loop.continue
    }
  }

  private[this] val nopPoll = new ce.kernel.Poll[<[*, Async & Abort[Throwable]]] {
    final override def apply[A](tsk: A < (Async & Abort[Throwable])) = tsk
  }

  val weakSync: ce.Sync[<[*, (Async & Abort[Throwable])]] = {

    new ce.Sync[<[*, (Async & Abort[Throwable])]] {

      final override def rootCancelScope: ce.kernel.CancelScope =
        ce.kernel.CancelScope.Cancelable

      final override def pure[A](a: A): A < (Async & Abort[Throwable]) =
        a

      final override def flatMap[A, B](fa: A < (Async & Abort[Throwable]))(f: A => B < (Async & Abort[Throwable])): B < (Async & Abort[Throwable]) =
        fa.flatMap(f)

      final override def suspend[A](hint: ce.Sync.Type)(thunk: => A): A < (Async & Abort[Throwable]) =
        Sync.defer { thunk }

      // These are not strictly necessary to run an Rxn,
      // but needed to initialize a `Reactive` instance:

      final override def uncancelable[A](body: ce.Poll[<[*, (Async & Abort[Throwable])]] => A < (Async & Abort[Throwable])): A < (Async & Abort[Throwable]) =
        body(nopPoll)

      // We don't need these:

      final override def raiseError[A](e: Throwable): A < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      final override def handleErrorWith[A](fa: A < (Async & Abort[Throwable]))(f: Throwable => A < (Async & Abort[Throwable])): A < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      final override def monotonic: FiniteDuration < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      final override def realTime: FiniteDuration < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      final override def tailRecM[A, B](a: A)(f: A => Either[A, B] < (Async & Abort[Throwable])): B < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      final override def forceR[A, B](fa: A < (Async & Abort[Throwable]))(fb: B < (Async & Abort[Throwable])): B < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      final override def canceled: Unit < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      final override def onCancel[A](fa: A < (Async & Abort[Throwable]), fin: Unit < (Async & Abort[Throwable])): A < (Async & Abort[Throwable]) =
        sys.error("not implemented")
    }
  }
}
