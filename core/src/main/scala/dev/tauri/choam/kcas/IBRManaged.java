/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam.kcas;

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

/**
 * Base class for objects managed by IBR
 */
public abstract class IBRManaged<T, M extends IBRManaged<T, M>> {

  // TODO: (look into acq/rel modes: http://gee.cs.oswego.edu/dl/html/j9mm.html)?
  // TODO: verify that we really don't need born_before from the paper

  private static final VarHandle BIRTH_EPOCH;

  private static final VarHandle RETIRE_EPOCH;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      BIRTH_EPOCH = l.findVarHandle(IBRManaged.class, "_birthEpoch", long.class);
      RETIRE_EPOCH = l.findVarHandle(IBRManaged.class, "_retireEpoch", long.class);
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @SuppressWarnings("unused")
  private volatile long _birthEpoch;

  @SuppressWarnings("unused")
  private volatile long _retireEpoch;

  /** Intrusive linked list (free/retired list) */
  private M _next;

  protected IBRManaged() {
    BIRTH_EPOCH.setOpaque(this, Long.MIN_VALUE);
    RETIRE_EPOCH.setOpaque(this, Long.MAX_VALUE);
  }

  final M getNext() {
    return this._next;
  }

  final void setNext(M n) {
    this._next = n;
  }

  final long getBirthEpochOpaque() {
    return (long) BIRTH_EPOCH.getOpaque(this);
  }

  final long getBirthEpochVolatile() {
    return (long) BIRTH_EPOCH.getVolatile(this);
  }

  final void setBirthEpochOpaque(long e) {
    BIRTH_EPOCH.setOpaque(this, e);
  }

  final long getRetireEpochOpaque() {
    return (long) RETIRE_EPOCH.getOpaque(this);
  }

  final long getRetireEpochVolatile() {
    return (long) RETIRE_EPOCH.getVolatile(this);
  }

  final void setRetireEpochOpaque(long e) {
    RETIRE_EPOCH.setOpaque(this, e);
  }

  /** Hook for subclasses for performing initialization */
  abstract protected void allocate(T tc);

  /** Hook for subclasses for performing cleanup when retiring */
  abstract protected void retire(T tc);

  /** Hook for subclasses for performing cleanup when freeing */
  abstract protected void free(T tc);
}
