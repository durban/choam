/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam;

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.AtomicReferenceArray;

abstract class PrimaryExchangerImplJvmBase {

  private static final VarHandle _INCOMING;
  private static final VarHandle _OUTGOING;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      _INCOMING = l.findVarHandle(PrimaryExchangerImplJvmBase.class, "_incoming", AtomicReferenceArray.class);
      _OUTGOING = l.findVarHandle(PrimaryExchangerImplJvmBase.class, "_outgoing", AtomicReferenceArray.class);
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  protected volatile AtomicReferenceArray<ExchangerNode<?>> _incoming;

  protected volatile AtomicReferenceArray<ExchangerNode<?>> _outgoing;

  protected boolean casIncoming(AtomicReferenceArray<ExchangerNode<?>> ov, AtomicReferenceArray<ExchangerNode<?>> nv) {
    return (boolean) _INCOMING.compareAndSet(this, ov, nv);
  }

  protected boolean casOutgoing(AtomicReferenceArray<ExchangerNode<?>> ov, AtomicReferenceArray<ExchangerNode<?>> nv) {
    return (boolean) _OUTGOING.compareAndSet(this, ov, nv);
  }
}
