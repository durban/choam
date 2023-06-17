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

import java.security.{ SecureRandom => JSecureRandom, NoSuchAlgorithmException }
import java.io.{ FileInputStream, EOFException }

// TODO: scala-js
// TODO: ref https://unix.stackexchange.com/questions/324209/when-to-use-dev-random-vs-dev-urandom#answer-324210

private[choam] object OsRandom {
  def mkNew(): OsRandom = {
    try {
      new WinRandom
    } catch {
      case _: NoSuchAlgorithmException =>
        new UnixRandom
    }
  }
}

private[choam] sealed abstract class OsRandom {

  def nextBytes(dest: Array[Byte]): Unit

  def nextBytes(n: Int): Array[Byte] = {
    require(n >= 0)
    val dest = new Array[Byte](n)
    if (n > 0) {
      nextBytes(dest)
    }
    dest
  }
}

private final class UnixRandom extends OsRandom {

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

  protected final def readFrom(s: FileInputStream, dest: Array[Byte]): Unit = {
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

private final class WinRandom extends OsRandom {

  private[this] val underlying: JSecureRandom =
    JSecureRandom.getInstance("Windows-PRNG")

  final override def nextBytes(dest: Array[Byte]): Unit = {
    if (dest.length > 0) {
      this.underlying.nextBytes(dest)
    }
  }
}
