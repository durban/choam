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

private[emcas] abstract class EmcasDescriptorBase {

  private[emcas] final def getStatusV(): Long = ???

  private[emcas] final def getStatusA(): Long = ???

  private[emcas] final def cmpxchgStatus(ov: Long, nv: Long): Long = ???

  private[emcas] final def getWordsP(): Array[WdLike[?]] = ???

  private[emcas] final def getWordsO(): Array[WdLike[?]] = ???

  private[emcas] final def setWordsO(words: Array[WdLike[?]]): Unit = ???

  final def getFallbackA(): EmcasDescriptor = ???

  final def cmpxchgFallbackA(ov: EmcasDescriptor, nv: EmcasDescriptor): EmcasDescriptor = ???

  final def getOrInitFallback(candidate: EmcasDescriptor): EmcasDescriptor = ???

  final def wasFinalized(wasSuccessful: Boolean): Unit = ???
}

private[emcas] object EmcasDescriptorBase {
  final val CLEARED: AnyRef = new AnyRef
}
