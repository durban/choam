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
package random

import java.nio.{ ByteBuffer, ByteOrder }

/** Beause Scala.js doesn't have StrictMath/VarHandle */
private abstract class RandomBasePlatform {

  protected final def strictMathSqrt(a: Double): Double =
    Math.sqrt(a) // TODO: ¯\_(ツ)_/¯

  protected final def strictMathLog(a: Double): Double =
    Math.log(a) // TODO: ¯\_(ツ)_/¯

  // TODO: These ByteBuffers are probably pretty
  // TODO: bad for performance, but this is JS.

  protected final def getLongAt0P(arr: Array[Byte]): Long = {
    ByteBuffer.wrap(arr, 0, 8).order(ByteOrder.LITTLE_ENDIAN).getLong()
  }

  protected final def putLongAtIdxP(arr: Array[Byte], idx: Int, nv: Long): Unit = {
    ByteBuffer.wrap(arr, idx, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(nv)
    ()
  }
}
