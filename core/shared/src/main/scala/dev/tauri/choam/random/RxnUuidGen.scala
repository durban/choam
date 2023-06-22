/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
package random

import java.util.UUID

import cats.effect.std.UUIDGen

private final class RxnUuidGen[X](rng: OsRng) extends RxnUuidGenBase with UUIDGen[Rxn[X, *]] {

  private[this] final val versionNegMask =
    0xffffffffffff0fffL

  private[this] final val version =
    0x0000000000004000L

  private[this] final val variantNegMask =
    0x3fffffffffffffffL

  private[this] final val variant =
    0x8000000000000000L

  final override def randomUUID: Rxn[X, UUID] = Rxn.unsafe.delay { _ =>
    val buff = new Array[Byte](16) // TODO: don't allocate (use a thread-local buffer)
    rng.nextBytes(buff)
    uuidFromRandomBytesInternal(buff)
  }

  private[this] final def uuidFromRandomBytesInternal(buff: Array[Byte]): UUID = {
    var msbs = getLongAt(buff, 0)
    var lsbs = getLongAt(buff, 8)
    msbs &= versionNegMask
    msbs |= version
    lsbs &= variantNegMask
    lsbs |= variant
    new UUID(msbs, lsbs)
  }

  private[random] final def uuidFromRandomBytes(buff: Array[Byte]): UUID = {
    uuidFromRandomBytesInternal(buff)
  }
}
