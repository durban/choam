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

import cats.~>
import cats.syntax.all._
import cats.effect.std.{ CountDownLatch => CatsCountDownLatch }

sealed abstract class CountDownLatch[F[_]] { self =>

  def release: Axn[Unit]

  def await: F[Unit]

  def toCats: CatsCountDownLatch[F]

  def mapK[G[_]](f: F ~> G): CountDownLatch[G] = {
    new CountDownLatch[G] {
      final def release: Axn[Unit] =
        self.release
      final def await: G[Unit] =
        f(self.await)
      final def toCats: CatsCountDownLatch[G] =
        self.toCats.mapK(f)
    }
  }
}

object CountDownLatch {

  final def apply[F[_]](count: Int)(implicit F: AsyncReactive[F]): Axn[CountDownLatch[F]] = {
    (Ref.padded(count), Promise.apply[F, Unit]).mapN { (c, p) =>
      new CountDownLatch[F] { self =>
        final val release: Axn[Unit] = {
          c.getAndUpdate { ov =>
            if (ov > 0) ov - 1
            else ov
          }.flatMapF { ov =>
            if (ov == 1) p.complete.provide(()).void
            else Axn.unit
          }
        }
        final def await: F[Unit] = {
          p.get
        }
        final def toCats: CatsCountDownLatch[F] = {
          new CatsCountDownLatch[F] {
            final def release: F[Unit] =
              F.run(self.release)
            final def await: F[Unit] =
              self.await
          }
        }
      }
    }
  }
}
