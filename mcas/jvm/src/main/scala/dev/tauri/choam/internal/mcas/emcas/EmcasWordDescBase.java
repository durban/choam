/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam.internal.mcas.emcas;

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

import dev.tauri.choam.internal.VarHandleHelper;

abstract class EmcasWordDescBase<A> {

  private static final VarHandle OV;
  private static final VarHandle NV;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      OV = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasWordDescBase.class, "_ov", Object.class));
      NV = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasWordDescBase.class, "_nv", Object.class));
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  private A _ov;
  private A _nv;

  EmcasWordDescBase(A ov, A nv) {
    this._ov = ov;
    this._nv = nv;
  }

  public final A ov() {
    return this.getOvP();
  }

  public final A nv() {
    return this.getNvP();
  }

  public final void wasFinalized(boolean wasSuccessful, A sentinel) {
    if (wasSuccessful) {
      OV.setOpaque(this, sentinel);
    } else {
      NV.setOpaque(this, sentinel);
    }
  }

  final A getOvP() {
    return (A) OV.get(this);
  }

  final A getNvP() {
    return (A) NV.get(this);
  }
}
