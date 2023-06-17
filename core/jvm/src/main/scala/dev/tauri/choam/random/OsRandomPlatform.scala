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

import java.io.{ FileInputStream, EOFException }
import java.security.{ SecureRandom => JSecureRandom, NoSuchAlgorithmException }

private[random] abstract class OsRandomPlatform {

  def mkNew(): OsRandom = {
    try {
      new WinRandom
    } catch {
      case _: NoSuchAlgorithmException =>
        new UnixRandom
    }
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

private final class WinRandom
  extends AdaptedOsRandom(JSecureRandom.getInstance("Windows-PRNG"))
