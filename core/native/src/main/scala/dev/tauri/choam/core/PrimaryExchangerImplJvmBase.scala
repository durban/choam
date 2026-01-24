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
package core

import java.util.concurrent.atomic.AtomicReferenceArray

import scala.scalanative.annotation.alwaysinline

import internal.AtomicHandle

private abstract class PrimaryExchangerImplJvmBase {

  @volatile
  @nowarn("cat=unused-privates")
  private[this] var __incoming: AtomicReferenceArray[ExchangerNode[?]] =
    _

  protected[this] final def _incoming: AtomicReferenceArray[ExchangerNode[?]] =
    __incoming

  @volatile
  @nowarn("cat=unused-privates")
  private[this] var __outgoing: AtomicReferenceArray[ExchangerNode[?]] =
    _

  protected[this] final def _outgoing: AtomicReferenceArray[ExchangerNode[?]] =
    __outgoing

  @alwaysinline
  private[this] final def atomicIncoming: AtomicHandle[AtomicReferenceArray[ExchangerNode[?]]] = {
    AtomicHandle(this, "__incoming")
  }

  @alwaysinline
  private[this] final def atomicOutgoing: AtomicHandle[AtomicReferenceArray[ExchangerNode[?]]] = {
    AtomicHandle(this, "__outgoing")
  }

  protected[this] final def cmpxchgIncoming(
    ov: AtomicReferenceArray[ExchangerNode[?]],
    nv: AtomicReferenceArray[ExchangerNode[?]]): AtomicReferenceArray[ExchangerNode[?]] = {
    atomicIncoming.compareAndExchange(ov, nv)
  }

  protected[this] final def cmpxchgOutgoing(
    ov: AtomicReferenceArray[ExchangerNode[?]],
    nv: AtomicReferenceArray[ExchangerNode[?]]): AtomicReferenceArray[ExchangerNode[?]] = {
    atomicOutgoing.compareAndExchange(ov, nv)
  }
}
