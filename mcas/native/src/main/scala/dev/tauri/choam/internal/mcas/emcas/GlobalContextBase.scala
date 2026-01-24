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
package emcas

import scala.scalanative.annotation.alwaysinline

private[emcas] abstract class GlobalContextBase(startCommitTs: Long) { // TODO: padding!

  @volatile
  @nowarn("cat=unused-privates")
  private[this] var commitTs: Long = startCommitTs
  // TODO: add padding between commitTs and threadCtxCount
  @volatile
  @nowarn("cat=unused-privates")
  private[this] var threadCtxCount: Long = _

  protected[this] final def yesWeNeedTheseFieldsEvenOnDotty(): Unit = {
    this.threadCtxCount : Unit
  }

  @alwaysinline
  private[this] final def atomicCommitTs: AtomicLongHandle = {
    AtomicLongHandle(this, "commitTs")
  }

  @alwaysinline
  private[this] final def atomicThreadCtxCount: AtomicLongHandle = {
    AtomicLongHandle(this, "threadCtxCount")
  }

  final def getCommitTs(): Long = {
    this.commitTs // volatile
  }

  final def cmpxchgCommitTs(ov: Long, nv: Long): Long = {
    atomicCommitTs.compareAndExchange(ov, nv)
  }

  final def getAndIncrThreadCtxCount(): Long = {
    this.getAndAddThreadCtxCount(1L)
  }

  final def getAndAddThreadCtxCount(x: Long): Long = {
    atomicThreadCtxCount.getAndAddAcquire(x)
  }

  @inline
  final def reachabilityFence(ref: AnyRef): Unit = {
    // TODO: we're assuming SN doesn't collect locals before a method returns...
  }
}

private[emcas] object GlobalContextBase {

  final def isVirtualThread(t: Thread): Boolean = { // TODO: in the future SN might support virtual threads
    false
  }

  final def registerEmcasJmxStats(emcas: Emcas): String = {
    null
  }

  final def unregisterEmcasJmxStats(objNameStr: String): Unit = {
    _assert(objNameStr eq null)
  }
}
