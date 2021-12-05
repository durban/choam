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

package dev.tauri.choam.kcas;

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

/**
 * Base class for objects managed by IBR
 *
 * The min/max epochs are mostly the same as birth/retire epochs
 * in the paper. However, there is no actual "retirement" as IBR
 * is used by `EMCAS`. Thus, min/max is a better terminology.
 *
 * Reserving any epoch number in the [min epoch, max epoch] closed
 * interval of a descriptor guarantees that that descriptor will
 * not be finalized.
 */
public abstract class IBRManaged<T, M extends IBRManaged<T, M>> {

  private static final VarHandle MIN_EPOCH;

  private static final VarHandle MAX_EPOCH;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      MIN_EPOCH = l.findVarHandle(IBRManaged.class, "_minEpoch", long.class);
      MAX_EPOCH = l.findVarHandle(IBRManaged.class, "_maxEpoch", long.class);
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @SuppressWarnings("unused")
  private volatile long _minEpoch;

  @SuppressWarnings("unused")
  private volatile long _maxEpoch;


  protected IBRManaged() {
    this.setMinEpochPlain(Long.MIN_VALUE);
    this.setMaxEpochPlain(Long.MAX_VALUE);
  }

  final long getMinEpochPlain() {
    return (long) MIN_EPOCH.get(this);
  }

  final long getMinEpochAcquire() {
    return (long) MIN_EPOCH.getAcquire(this);
  }

  final long getMinEpochVolatile() {
    return (long) MIN_EPOCH.getVolatile(this);
  }

  final void setMinEpochPlain(long e) {
    MIN_EPOCH.set(this, e);
  }

  final void decreaseMinEpochRelease(long by) {
    MIN_EPOCH.getAndAddRelease(this, -by);
  }

  final long getMaxEpochPlain() {
    return (long) MAX_EPOCH.get(this);
  }

  final long getMaxEpochAcquire() {
    return (long) MAX_EPOCH.getAcquire(this);
  }

  final long getMaxEpochVolatile() {
    return (long) MAX_EPOCH.getVolatile(this);
  }

  final void setMaxEpochPlain(long e) {
    MAX_EPOCH.set(this, e);
  }

  final void increaseMaxEpochRelease(long by) {
    MAX_EPOCH.getAndAddRelease(this, by);
  }
}
