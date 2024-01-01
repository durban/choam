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

package dev.tauri.choam
package random

import java.io.{ FileInputStream, EOFException }
import java.security.{ SecureRandom => JSecureRandom, NoSuchAlgorithmException }

private[random] abstract class OsRngPlatform {

  /**
   * Creates (and initializes) a new `OsRng`
   * instance, which will get secure random
   * bytes directly from the OS.
   *
   * Strategy on the JVM:
   *
   * - If the [[java.security.SecureRandom]] instance
   *   called `Windows-PRNG` is available (i.e., we're
   *   on Windows), we use that. It uses the Windows
   *   CrytoAPI (the `CryptGenRandom` call) to get
   *   secure random bytes. According to the [white
   *   paper](https://aka.ms/win10rng), this involves
   *   a lock, but there is "very low contention" (page
   *   6). This is probably the best we can do on Windows.
   *
   * - Otherwise (i.e., we're *not* on Windows), we assume
   *   that Unix-like `/dev/random` and `/dev/urandom`
   *   devices are available. We first read some bytes from
   *   `/dev/random` (to make sure the kernel RNG is initialized,
   *   because otherwise, e.g., older Linux may return non-random
   *   data form `/dev/urandom`), then return an `OsRng` which
   *   will read from `/dev/urandom` (which is non-blocking).
   *   See [this description](https://unix.stackexchange.com/questions/324209/when-to-use-dev-random-vs-dev-urandom#answer-324210)
   *   about [u]random on various Unix-like systems.
   *
   * - Otherwise (e.g., if the files don't exist, or we
   *   can't read them) we give up, and throw a [[java.io.IOException]].
   *
   * Note: this method may block (e.g., read from
   * `/dev/random`), buf after that, the returned
   * `OsRng` will (most likely) will not.
   */
  def mkNew(): OsRng = {
    try {
      val wprng = JSecureRandom.getInstance("Windows-PRNG")
      new WinRng(wprng)
    } catch {
      case _: NoSuchAlgorithmException =>
        new UnixRng
    }
  }
}

private final class WinRng(sr: JSecureRandom)
  extends AdaptedOsRng(sr) {

  require(sr.getAlgorithm() == "Windows-PRNG") // just to be sure
}

private final class UnixRng extends OsRng {

  private[this] val stream: FileInputStream = {
    val random = new FileInputStream("/dev/random")
    try {
      val tmp = new Array[Byte](8)
      readFrom(random, tmp)
    } finally {
      random.close()
    }
    new FileInputStream("/dev/urandom")
  }

  final override def nextBytes(dest: Array[Byte]): Unit = {
    readFrom(stream, dest)
  }

  private[this] final def readFrom(s: FileInputStream, dest: Array[Byte]): Unit = {
    var remaining = dest.length
    var offset = 0
    while (remaining > 0) {
      val read = s.read(dest, offset, remaining)
      if (read <= 0) {
        // this shouldn't happen, we must always make progress
        throw new EOFException
      }
      remaining -= read
      offset += read
    }
  }
}
