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
package mcas
package amcas

import java.util.concurrent.atomic.{ AtomicLong, AtomicReferenceArray }

private final class AmcasDescriptor private[this] (
  val state: AtomicLong, // TODO: do we need this? (later probably yes, due to versions)
  addresses: Array[MemoryLocation[AnyRef]],
  ovs: Array[AnyRef], // TODO: ovs and nvs could be one single array (with double length)
  nvs: Array[AnyRef],
  val oldVersions: Array[Long], // TODO: actually have versions...
  val mchs: AtomicReferenceArray[McasHelper],
) {

  _assert({
    val len = addresses.length
    (ovs.length == len) && (nvs.length == len) && (oldVersions.length == len) && (mchs.length() == len)
  })

  private[amcas] final def this(
    addresses: Array[MemoryLocation[AnyRef]],
    ovs: Array[AnyRef],
    nvs: Array[AnyRef],
  ) = this(
    new AtomicLong,
    addresses,
    ovs,
    nvs,
    sys.error("TODO"),
    new AtomicReferenceArray(addresses.length),
  )

  final def length: Int = {
    addresses.length
  }

  final def lastIdx: Int = {
    addresses.length - 1
  }

  final def address(idx: Int): MemoryLocation[AnyRef] = {
    addresses(idx)
  }

  final def expectedValue(idx: Int): AnyRef = {
    ovs(idx)
  }

  final def newValue(idx: Int): AnyRef = {
    nvs(idx)
  }
}
