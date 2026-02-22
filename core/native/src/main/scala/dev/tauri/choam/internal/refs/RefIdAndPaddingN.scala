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
package refs

private abstract class RefIdAndPaddingN(private[this] val _id: Long) extends PaddingN {

  final def id: Long =
    _id

  final override def hashCode: Int = {
    // `RefIdGen` generates IDs with
    // Fibonacci hashing, so no need
    // to hash them here even further.
    // However, we use the upper 32
    // bits, because they're likely
    // a better hash (due to
    // multiplicative hashing).
    // Using `_id` also means that
    // `Ref2` hash will not consider
    // the ID of the 2nd `Ref`. But
    // that's fine, as the IDs of the
    // 2 refs are very likely close to
    // each other, and, e.g., XORing
    // them would actually be worse.
    (this.id >>> 32).toInt
    // IDs are globally unique, so the
    // default `equals` (based on object
    // identity) is fine for us.
  }
}
