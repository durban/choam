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
package internal

import scala.concurrent.duration._

import cats.effect.unsafe.{ IORuntime, IORuntimeBuilder }

import munit.CatsEffectSuite

/**
 * Note: this class is duplicated on JVM/Native and JS
 *
 * We make our own `CatsEffectSuite`, because some of
 * the defaults are not good for us: (1) the default
 * timeout is not enough for a lot of our tests (in CI);
 * and (2) we don't want the default polling system for
 * the CE runtime.
 */
abstract class ChoamCatsEffectSuite extends CatsEffectSuite {

  final override def munitIOTimeout: Duration =
    super.munitIOTimeout * 2

  implicit override def munitIORuntime: IORuntime = {
    ChoamCatsEffectSuite.ioRt
  }
}

private object ChoamCatsEffectSuite {

  /**
   * Note: we rely on this being lazily initialized
   * (when this companion object is used, NOT when a
   * ChoamCatsEffectSuite instance is created).
   *
   * Also, this is never shut down; we rely on this
   * being used from sbt with `Test / fork := true`.
   */
  private val ioRt: IORuntime = {
    IORuntimeBuilder().setPollingSystem(cats.effect.unsafe.SleepSystem).build()
  }
}
