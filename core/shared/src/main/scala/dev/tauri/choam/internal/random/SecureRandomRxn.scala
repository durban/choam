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
package random

import cats.effect.std.SecureRandom

import core.Rxn

/**
 * Implements [[cats.effect.std.SecureRandom]] by using
 * the OS CSPRNG (through `OsRng`). This is as nonblocking
 * as possible on each platform (see `OsRng`).
 */
private final class SecureRandomRxn
  extends RandomBase with SecureRandom[Rxn] {

  final override def nextBytes(n: Int): Rxn[Array[Byte]] = Rxn.unsafe.delayContext { ctx =>
    ctx.impl.osRng.nextBytes(n)
  }
}
