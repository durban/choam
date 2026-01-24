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

import java.lang.invoke.VarHandle

import scala.scalanative.annotation.alwaysinline

private[emcas] abstract class EmcasDescriptorBase {

  // TODO: padding (although, it might increase GC pressure?)
  @volatile
  @nowarn("cat=unused-privates")
  private[this] var _status: Long =
    Version.Active

  @nowarn("cat=unused-privates")
  private[this] var _words: Array[WdLike[?]] =
    _

  @volatile
  @nowarn("cat=unused-privates")
  private[this] var _fallback: EmcasDescriptor =
    _

  protected[this] final def yesWeNeedTheseFieldsEvenOnDotty(): Unit = {
    this._fallback : Unit
  }

  @alwaysinline
  private[this] final def atomicStatus: AtomicLongHandle = {
    AtomicLongHandle(this, "_status")
  }

  @alwaysinline
  private[this] final def atomicWords: AtomicHandle[Array[WdLike[?]]] = {
    AtomicHandle(this, "_words")
  }

  @alwaysinline
  private[this] final def atomicFallback: AtomicHandle[EmcasDescriptor] = {
    AtomicHandle(this, "_fallback")
  }

  private[emcas] final def getStatusV(): Long = {
    this._status // volatile
  }

  private[emcas] final def getStatusA(): Long = {
    atomicStatus.getAcquire
  }

  private[emcas] final def cmpxchgStatus(ov: Long, nv: Long): Long = {
    atomicStatus.compareAndExchange(ov, nv)
  }

  private[emcas] final def getWordsP(): Array[WdLike[?]] = {
    this._words
  }

  private[emcas] final def getWordsO(): Array[WdLike[?]] = {
    atomicWords.getOpaque
  }

  private[emcas] final def setWordsO(words: Array[WdLike[?]]): Unit = {
    atomicWords.setOpaque(words)
  }

  final def getFallbackA(): EmcasDescriptor = {
    atomicFallback.getAcquire
  }

  final def cmpxchgFallbackA(ov: EmcasDescriptor, nv: EmcasDescriptor): EmcasDescriptor = {
    atomicFallback.compareAndExchangeAcquire(ov, nv)
  }

  final def getOrInitFallback(candidate: EmcasDescriptor): EmcasDescriptor = {
    val wit: EmcasDescriptor = atomicFallback.compareAndExchangeRelAcq(null : EmcasDescriptor, candidate)
    if (wit eq null) {
      candidate
    } else {
      wit
    }
  }

  final def wasFinalized(wasSuccessful: Boolean): Unit = {
    val words = this.getWordsO()
    val len = words.length
    VarHandle.releaseFence()
    this.setWordsO(null)
    val sentinel = EmcasDescriptorBase.CLEARED
    var idx = 0
    while (idx < len) {
      val wd = words(idx).asInstanceOf[WdLike[AnyRef]]
      words(idx) = null // TODO: should be opaque store
      wd.wasFinalized(wasSuccessful, sentinel)
      idx += 1
    }
  }
}

private[emcas] object EmcasDescriptorBase {
  final val CLEARED: AnyRef = new AnyRef
}
