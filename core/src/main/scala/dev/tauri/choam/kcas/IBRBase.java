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

/** Base class for IBR, containing the global epoch number */
abstract class IBRBase {

  private static final VarHandle VALUE;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      VALUE = l.findVarHandle(IBRBase.class, "_epoch", long.class);
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  // TODO: check if 64 bits is enough (overflow)
  @SuppressWarnings("unused")
  private long _epoch;

  IBRBase(long zeroEpoch) {
    VALUE.setRelease(this, zeroEpoch);
  }

  // We're incrementing the global epoch with
  // `getAndAddRelease`, which is atomic, so
  // concurrent increments will not be lost.
  // We're reading it with `getAcquire`, so
  // we'll write correct values into blocks
  // when retiring.

  protected final long getEpoch() {
    return (long) VALUE.getAcquire(this);
  }

  protected final void incrementEpoch() {
    VALUE.getAndAddRelease(this, 1L);
  }
}
