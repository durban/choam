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

import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.kernel.{ Async, Sync, Spawn }
import cats.syntax.all._
import cats.effect.kernel.syntax.spawn._

private[choam] object IOCancel {

  def stoppable[F[_], A](task: AtomicBoolean => F[A])(implicit F: Async[F]): F[A] = {
    stoppable1(task)(F, F)
  }

  // Original version
  def stoppable1[F[_], A](task: AtomicBoolean => F[A])(implicit F: Spawn[F], S: Sync[F]): F[A] = {
    F.flatMap(S.delay { new AtomicBoolean(false) }) { stopSignal =>
      val tsk: F[A] = task(stopSignal)
      F.uncancelable { poll =>
        F.flatMap(F.start(tsk)) { fiber =>
          F.onCancel(
            fa = poll(fiber.joinWithNever),
            fin = F.productR(S.delay { stopSignal.set(true) })(
              // so that cancel backpressures until
              // the task actually observes the signal
              // and stops whatever it is doing:
              F.void(fiber.joinWithNever)
            )
          )
        }
      }
    }
  }

  // See https://github.com/typelevel/cats-effect/discussions/2671
  def stoppableSimpler[F[_], A](task: AtomicBoolean => F[A])(implicit F: Async[F]): F[A] = {
    F.async { cb =>
      F.defer {
        val flag = new AtomicBoolean(false)
        val runner = task(flag).attempt.flatMap { e =>
          F.delay { cb(e) }
        }
        runner.start.map { fiber =>
          Some(F.delay { flag.set(true) } *> fiber.joinWithNever.void)
        }
      }
    }
  }
}
