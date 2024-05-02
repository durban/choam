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
package internal
package mcas

private[mcas] abstract class AbstractHamt protected[mcas] () {

  protected def contentsArr: Array[AnyRef]

  final def toString(pre: String, post: String): String = {
    val sb = new java.lang.StringBuilder(pre)
    val _ = this.toStringInternal(sb, first = true)
    sb.append(post)
    sb.toString()
  }

  private final def toStringInternal(sb: java.lang.StringBuilder, first: Boolean): Boolean = {
    val contents = this.contentsArr
    var i = 0
    val len = contents.length
    var fst = first
    while (i < len) {
      contents(i) match {
        case null =>
          ()
        case node: AbstractHamt =>
          fst = node.toStringInternal(sb, fst)
        case a =>
          if (!fst) {
            sb.append(", ")
          } else {
            fst = false
          }
          sb.append(a.toString)
      }
      i += 1
    }
    fst
  }
}
