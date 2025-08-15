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

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.unsigned.{ UnsignedRichInt, UInt }
import scala.scalanative.unsafe.{ extern, link, CSize, CVoidPtr, CUnsignedLong, CInt, Ptr, UnsafeRichArray }
import scala.scalanative.posix.unistd
import scala.scalanative.libc.errno

private[mcas] abstract class OsRngPlatform {

  final def mkNew(): OsRng = {
    val osRng = new NativeOsRng
    // getentropy might block once, at the beginning;
    // make sure that's now, and not later, when we're
    // running an Rxn:
    val tmp = new Array[Byte](8)
    osRng.nextBytes(tmp)
    // ok, we're done:
    osRng
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
    getentropy(start, size)
    val rem = remaining - size
    if (rem > 0.toUSize) {
      nextBytes(start + size, rem)
    } // else: we're done
  }

  @inline
  private[this] final def getentropy(buffer: CVoidPtr, length: CSize): Unit = {
    if (LinktimeInfo.isWindows) {
      win(buffer, length)
    } else {
      unix(buffer, length)
    }
  }

  private[this] final def unix(buffer: CVoidPtr, length: CSize): Unit = {
    val res = unistd.getentropy(buffer, length)
    if (res != 0) {
      val no: CInt = errno.errno
      throw new RuntimeException(s"getentropy returned ${res} (errno == ${no})")
    }
  }

  private[this] final def win(buffer: CVoidPtr, length: CSize): Unit = {
    val status: UInt = Bcrypt.BCryptGenRandom(
      null,
      buffer,
      length,
      0x00000002.toCSize, // BCRYPT_USE_SYSTEM_PREFERRED_RNG
    )
    if (status > 0x7FFFFFFF.toUInt) {
      throw new RuntimeException(s"BCryptGenRandom returned ${status}")
    }
  }

  final override def close(): Unit = {
    ()
  }
}

@extern
@link("bcrypt")
private object Bcrypt { // win Bcrypt.h
  def BCryptGenRandom(
    hAlgorithm: CVoidPtr,
    pbBuffer: CVoidPtr,
    cbBuffer: CUnsignedLong,
    dwFlags: CUnsignedLong,
  ): UInt = extern
}
