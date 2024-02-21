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
package refs

private object CompatPlatform {

  final type AtomicReferenceArray[A] =
    _root_.dev.tauri.choam.refs.AtomicReferenceArray[A]

  final def checkArrayIndexIfScalaJs(idx: Int, length: Int): Unit = {
    // Out-of-bounds array indexing is undefined behavior(??) on scala-js,
    // so we need this extra check here (on the JVM, we rely on arrays working):
    if ((idx < 0) || (idx >= length)) {
      throw new ArrayIndexOutOfBoundsException(s"Index ${idx} out of bounds for length ${length}")
    }
  }
}
