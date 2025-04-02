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

import cats.effect.kernel.Async

import munit.CatsEffectSuite

final class BaseSpecZIOSpec
  extends BaseSpecZIO
  with SpecDefaultMcas {

  import zio.ZIO

  test("Test success") {
    ZIO.attempt { () }
  }

  test("Test ignore".ignore) {
    ZIO.attempt { throw new AssertionError }
  }

  test("Test expected failure (exception)".fail) {
    ZIO.attempt { throw new AssertionError }
  }

  test("Test expected failure (forked exception)".fail) {
    ZIO.attempt { throw new AssertionError }.forkDaemon.flatMap { fiber =>
      fiber.join
    }
  }
}

abstract class BaseSpecZIO
  extends CatsEffectSuite
  with BaseSpecAsyncF[zio.Task]
  with UtilsForZIO { this: McasImplSpec =>

  private[this] val runtime =
    zio.Runtime.default

  final override def F: Async[zio.Task] =
    zio.interop.catz.asyncInstance

  protected final override def absolutelyUnsafeRunSync[A](fa: zio.Task[A]): A = {
    zio.Unsafe.unsafe { implicit u =>
      this.runtime.unsafe.run(fa).getOrThrow()
    }
  }

  private def transformZIO: ValueTransform = {
    new this.ValueTransform(
      "ZIO",
      { case x: zio.ZIO[_, _, _] =>
        val tsk = x.asInstanceOf[zio.Task[_]]
        val tskWithLogCfg = zio.ZIO.scopedWith { scope =>
          zio.Runtime.setUnhandledErrorLogLevel(this.zioUnhandledErrorLogLevel).build(scope) *> tsk
        }
        zio.Unsafe.unsafe { implicit u =>
          this.runtime.unsafe.runToFuture(tskWithLogCfg)
        }
      }
    )
  }

  override def munitValueTransforms: List[this.ValueTransform] = {
    super.munitValueTransforms :+ this.transformZIO
  }

  override def munitIgnore: Boolean = {
    super.munitIgnore || this.isOpenJ9()
  }
}
