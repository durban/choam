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

import core.Reactive

import kyo.{ <, Sync, Async, Abort }

final class Converter(rt: ChoamRuntime) {

  final def syncInstance: ce.Sync[<[*, (Async & Abort[Throwable])]] = {
    this.weakSync
  }

  final def reactiveInstance: Reactive[<[*, (Async & Abort[Throwable])]] = {
    this._reactiveInstance
  }

  private[this] val weakSync: ce.Sync[<[*, (Async & Abort[Throwable])]] = {
    new ce.Sync[<[*, (Async & Abort[Throwable])]] {

      private[this] val nopPoll = {
        new ce.Poll[<[*, Async & Abort[Throwable]]] {
          final override def apply[B](tsk: B < (Async & Abort[Throwable])): B < (Async & Abort[Throwable]) =
            tsk
        }
      }

      override def rootCancelScope: ce.kernel.CancelScope =
        ce.kernel.CancelScope.Cancelable

      override def pure[A](a: A): A < (Async & Abort[Throwable]) =
        a

      override def flatMap[A, B](fa: A < (Async & Abort[Throwable]))(f: A => B < (Async & Abort[Throwable])): B < (Async & Abort[Throwable]) =
        fa.flatMap(f)

      override def uncancelable[A](body: ce.Poll[<[*, (Async & Abort[Throwable])]] => A < (Async & Abort[Throwable])): A < (Async & Abort[Throwable]) =
        body(nopPoll) // TODO: this doesn't actually makes it uncancelable

      override def suspend[A](hint: ce.Sync.Type)(thunk: => A): A < (Async & Abort[Throwable]) =
        Sync.defer { thunk }

      // These are not strictly necessary to run an Rxn:

      override def raiseError[A](e: Throwable): A < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      override def handleErrorWith[A](fa: A < (Async & Abort[Throwable]))(f: Throwable => A < (Async & Abort[Throwable])): A < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      override def monotonic: FiniteDuration < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      override def realTime: FiniteDuration < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      override def tailRecM[A, B](a: A)(f: A => Either[A, B] < (Async & Abort[Throwable])): B < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      override def forceR[A, B](fa: A < (Async & Abort[Throwable]))(fb: B < (Async & Abort[Throwable])): B < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      override def canceled: Unit < (Async & Abort[Throwable]) =
        sys.error("not implemented")

      override def onCancel[A](fa: A < (Async & Abort[Throwable]), fin: Unit < (Async & Abort[Throwable])): A < (Async & Abort[Throwable]) =
        sys.error("not implemented")
    }
  }

  private[this] val _reactiveInstance =
    new Reactive.SyncReactive(rt.mcasImpl)(using weakSync)
}
