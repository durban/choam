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

import scala.scalanative.unsafe.{ Ptr, UnsafeRichArray }

private abstract class RandomBasePlatformBase {

  private[random] final def getLongAt0P(arr: Array[Byte]): Long = {
    val ptr: Ptr[Byte] = arr.at(0)
    val longPtr: Ptr[Long] = ptr.asInstanceOf[Ptr[Long]] // TODO: is this cast safe?
    !longPtr
  }

  private[random] final def putLongAtIdxP(arr: Array[Byte], idx: Int, nv: Long): Unit = {
    val ptr: Ptr[Byte] = arr.at(idx)
    val longPtr: Ptr[Long] = ptr.asInstanceOf[Ptr[Long]] // TODO: is this cast safe?
    !longPtr = nv
  }
}
