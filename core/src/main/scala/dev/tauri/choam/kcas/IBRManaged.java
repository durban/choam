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


  protected IBRManaged() {
    this.setBirthEpochPlain(Long.MIN_VALUE);
    this.setRetireEpochPlain(Long.MAX_VALUE);
  }

  final long getBirthEpochPlain() {
    return (long) BIRTH_EPOCH.get(this);
  }

  final long getBirthEpochAcquire() {
    return (long) BIRTH_EPOCH.getAcquire(this);
  }

  final long getBirthEpochVolatile() {
    return (long) BIRTH_EPOCH.getVolatile(this);
  }

  final void setBirthEpochPlain(long e) {
    BIRTH_EPOCH.set(this, e);
  }

  final void decreaseBirthEpochRelease(long by) {
    BIRTH_EPOCH.getAndAddRelease(this, -by);
  }

  final long getRetireEpochPlain() {
    return (long) RETIRE_EPOCH.get(this);
  }

  final long getRetireEpochAcquire() {
    return (long) RETIRE_EPOCH.getAcquire(this);
  }

  final long getRetireEpochVolatile() {
    return (long) RETIRE_EPOCH.getVolatile(this);
  }

  final void setRetireEpochPlain(long e) {
    RETIRE_EPOCH.set(this, e);
  }

  final void increaseRetireEpochRelease(long by) {
    RETIRE_EPOCH.getAndAddRelease(this, by);
  }

  /** Hook for subclasses for performing initialization */
  abstract protected void allocate(T tc);

  /** Hook for subclasses for performing cleanup when retiring */
  abstract protected void retire(T tc);

  /** Hook for subclasses for performing cleanup when freeing */
  abstract protected void free(T tc);
}
