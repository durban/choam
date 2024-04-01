/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam.core;

import java.util.concurrent.TimeUnit;

import scala.concurrent.duration.FiniteDuration;

abstract class BackoffPlatform {

  // see the comment in Backoff2 about these values
  static final int maxPauseDefault = 4096;
  static final int maxCedeDefault = 8;
  static final int maxSleepDefault = 8;
  static final int sleepAtomShiftNs = 23;

  static final boolean randomizePauseDefault = true;
  static final boolean randomizeCedeDefault = true;
  static final boolean randomizeSleepDefault = true;

  /**
   * We sleep whole multiples of this.
   */
  static final long sleepAtomNanos =
    8000000L;

  static final FiniteDuration maxSleepDefaultDuration =
    new FiniteDuration(maxSleepDefault * sleepAtomNanos, TimeUnit.NANOSECONDS);

  // marker bits for `backoff`:
  static final long backoffSpinMark = 1L << 32;
  static final long backoffCedeMark = 2L << 32;
  static final long backoffSleepMark = 3L << 32;
  static final long backoffTokenMask = 0xFFFFFFFFL;

  final void once() {
    Thread.onSpinWait();
  }
}
