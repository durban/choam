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
package async

import java.util.concurrent.atomic.AtomicReference

import cats.effect.kernel.Async

private object SideChannel {

  final def apply[F[_], A](implicit F: Async[F]): F[SideChannel[F, A]] = {
    F.delay { new SideChannel(new AtomicReference) }
  }
}

private final class SideChannel[F[_], A] private (
  state: AtomicReference[Either[Either[Throwable, A] => Unit, A]],
)(implicit F: Async[F]) {

  final def get: F[A] = {
    F.asyncCheckAttempt[A] { cb =>
      F.delay {

        @tailrec
        def go(): Either[Option[F[Unit]], A] = {
          state.get() match {
            case null =>
              if (state.compareAndSet(null, Left(cb))) {
                val cancel: F[Unit] = F.delay {
                  impossible("SideChannel#get was cancelled")
                }
                Left(Some(cancel))
              } else {
                go()
              }
            case Left(_) =>
              impossible("SideChannel#get found another subscriber")
            case r @ Right(_) =>
              r.asInstanceOf[Right[Nothing, A]]
          }
        }

        go()
      }
    }
  }

  @tailrec
  final def completeSync(ra: Right[Nothing, A]): Boolean = {
    state.get() match {
      case null =>
        if (state.compareAndSet(null, ra)) {
          true
        } else {
          completeSync(ra)
        }
      case l @ Left(cb) =>
        if (state.compareAndSet(l, ra)) {
          cb(ra)
          true
        } else {
          completeSync(ra)
        }
      case Right(_) =>
        false
    }
  }
}
