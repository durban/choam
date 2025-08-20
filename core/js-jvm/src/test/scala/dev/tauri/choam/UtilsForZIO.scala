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

import munit.Location

trait UtilsForZIO { this: BaseSpecAsyncF[zio.Task] & McasImplSpec =>

  /** Subclasses may raise this to INFO, if they're interested */
  protected def zioUnhandledErrorLogLevel: zio.LogLevel =
    zio.LogLevel.Debug

  final override def assertResultF[A, B](obtained: zio.Task[A], expected: B, clue: String = "values are not the same")(
    implicit loc: Location, ev: B <:< A
  ): zio.Task[Unit] = {
    obtained.flatMap(ob => zio.ZIO.attempt { this.assertEquals(ob, expected, clue) })
  }

  final override def assumeNotZio: zio.Task[Unit] = {
    this.assumeF(false)
  }
}

object UtilsForZIO {

  implicit def asyncInstanceForZioTask: Async[zio.Task] =
    _catsEffectZioInstances.asyncInstance[Any]

  private[this] val _catsEffectZioInstances =
    new zio.interop.CatsEffectInstances {}
}
