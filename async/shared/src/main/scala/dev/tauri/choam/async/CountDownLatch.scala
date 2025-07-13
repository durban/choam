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

import cats.syntax.all._
import cats.effect.std.{ CountDownLatch => CatsCountDownLatch }

import core.{ Rxn, Ref, AsyncReactive }

sealed abstract class CountDownLatch private () { self =>

  def release: Rxn[Unit]

  def await[F[_]](implicit F: AsyncReactive[F]): F[Unit]

  def toCats[F[_]](implicit F: AsyncReactive[F]): CatsCountDownLatch[F]
}

object CountDownLatch {

  final def apply(count: Int, str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default): Rxn[CountDownLatch] = {
    (Ref(count, str), Promise[Unit](str)).mapN { (c, p) =>
      new CountDownLatch { self =>
        final override val release: Rxn[Unit] = {
          c.getAndUpdate { ov =>
            if (ov > 0) ov - 1
            else ov
          }.flatMap { ov =>
            if (ov == 1) p.complete(()).void
            else Rxn.unit
          }
        }
        final override def await[F[_]](implicit F: AsyncReactive[F]): F[Unit] = {
          p.get[F]
        }
        final override def toCats[F[_]](implicit F: AsyncReactive[F]): CatsCountDownLatch[F] = {
          new CatsCountDownLatch[F] {
            final def release: F[Unit] =
              F.run(self.release)
            final def await: F[Unit] =
              self.await[F]
          }
        }
      }
    }
  }
}
