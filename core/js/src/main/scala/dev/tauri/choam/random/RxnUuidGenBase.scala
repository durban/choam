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
package random

import java.util.UUID
import java.nio.{ ByteBuffer, ByteOrder }

import internal.mcas.Mcas

// Note: this class/object is duplicated for JVM/JS
private object RxnUuidGenBase {

  private[this] final val versionNegMask =
    0xffffffffffff0fffL

  private[this] final val version =
    0x0000000000004000L

  private[this] final val variantNegMask =
    0x3fffffffffffffffL

  private[this] final val variant =
    0x8000000000000000L

  final def unsafeRandomUuidInternal(ctx: Mcas.ThreadContext): UUID = {
    val buff = new Array[Byte](16) // TODO: don't allocate (use a thread-local buffer)
    ctx.impl.osRng.nextBytes(buff)
    uuidFromRandomBytesInternal(buff)
  }

  final def uuidFromRandomBytes(buff: Array[Byte]): UUID = {
    uuidFromRandomBytesInternal(buff)
  }

  private[this] final def uuidFromRandomBytesInternal(buff: Array[Byte]): UUID = {
    var msbs = getLongAtP(buff, 0)
    var lsbs = getLongAtP(buff, 8)
    msbs &= versionNegMask
    msbs |= version
    lsbs &= variantNegMask
    lsbs |= variant
    new UUID(msbs, lsbs)
  }

  private[this] final def getLongAtP(arr: Array[Byte], offset: Int): Long = {
    ByteBuffer.wrap(arr, offset, 8).order(ByteOrder.BIG_ENDIAN).getLong()
  }
}
