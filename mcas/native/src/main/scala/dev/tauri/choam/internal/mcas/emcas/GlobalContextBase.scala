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
package emcas

private[emcas] abstract class GlobalContextBase {

  final def getCommitTs(): Long = ???

  final def cmpxchgCommitTs(ov: Long, nv: Long): Long = ???

  final def getAndIncrThreadCtxCount(): Long = ???

  final def getAndAddThreadCtxCount(x: Long): Long = ???
}

private[emcas] object GlobalContextBase {

  final def registerEmcasJmxStats(emcas: Emcas): String = {
    null
  }

  final def unregisterEmcasJmxStats(objNameStr: String): Unit = {
    _assert(objNameStr eq null)
  }
}
