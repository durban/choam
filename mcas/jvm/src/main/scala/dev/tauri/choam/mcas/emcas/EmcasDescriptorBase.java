/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam.mcas.emcas;

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

import dev.tauri.choam.mcas.McasStatus;

abstract class EmcasDescriptorBase {

  private static final VarHandle STATUS;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      STATUS = l.findVarHandle(EmcasDescriptorBase.class, "_status", long.class);
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  // TODO: padding (although, it might increase GC pressure?)
  private volatile long _status =
    McasStatus.Active;

  long getStatus() {
    return this._status; // volatile
  }

  protected boolean casStatusInternal(long ov, long nv) {
    return STATUS.compareAndSet(this, ov, nv);
  }

  long cmpxchgStatus(long ov, long nv) {
    return (long) STATUS.compareAndExchange(this, ov, nv);
  }
}