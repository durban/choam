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
package internal
package mcas

import scala.scalanative.unsigned.UnsignedRichInt
import scala.scalanative.unsafe.{ CSize, CInt, Ptr, UnsafeRichArray }
import scala.scalanative.posix.unistd.getentropy
import scala.scalanative.libc.errno

private[mcas] abstract class OsRngPlatform {

  final def mkNew(): OsRng = {
    new NativeOsRng
  }
}

private final class NativeOsRng extends OsRng {

  final override def nextBytes(dest: Array[Byte]): Unit = {
    val ptr = dest.at(0)
    nextBytes(ptr, dest.length.toCSize)
  }

  @tailrec
  private[this] final def nextBytes(start: Ptr[Byte], remaining: CSize): Unit = {
    val max = 256.toUSize
    val size = if (remaining > max) max else remaining
    val res = getentropy(start, size) // TODO: what about windows?
    if (res == 0) {
      val rem = remaining - size
      if (rem > 0.toUSize) {
        nextBytes(start + size, rem)
      } // else: we're done
    } else {
      val no: CInt = errno.errno
      throw new RuntimeException(s"getentropy returned ${res} (errno == ${no})")
    }
  }

  final override def close(): Unit = {
    ()
  }
}
