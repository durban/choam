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

import scala.concurrent.duration._

private abstract class BackoffPlatform {

  @inline
  final def once(): Unit = {
    Thread.onSpinWait()
  }
}

private object BackoffPlatform {

  // these are duplicated from BackoffPlatform.java:

  @inline final val maxPauseDefault = 4096
  @inline final val maxCedeDefault = 8
  @inline final val maxSleepDefault = 8
  @inline final val sleepAtomShiftNs = 23

  @inline final val randomizePauseDefault = true
  @inline final val randomizeCedeDefault = true
  @inline final val randomizeSleepDefault = true

  @inline final val sleepAtomNanos =
    8000000L

  final val maxSleepDefaultDuration: FiniteDuration =
    (maxSleepDefault * sleepAtomNanos).nanoseconds

  @inline final val backoffSpinMark = 1L << 32;
  @inline final val backoffCedeMark = 2L << 32;
  @inline final val backoffSleepMark = 3L << 32;
  @inline final val backoffTokenMask = 0xFFFFFFFFL;
}
