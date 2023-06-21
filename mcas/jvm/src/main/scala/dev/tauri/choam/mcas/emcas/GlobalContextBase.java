/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import dev.tauri.choam.vhandle.VarHandleHelper;
import dev.tauri.choam.mcas.Version;
import dev.tauri.choam.mcas.PaddedMemoryLocationPadding;

abstract class GlobalContextBase extends PaddedMemoryLocationPadding {

  private static final VarHandle COMMIT_TS;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      COMMIT_TS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(GlobalContextBase.class, "commitTs", long.class));
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private volatile long commitTs = Version.Start;

  final long getCommitTs() {
    return this.commitTs; // volatile
  }

  final long cmpxchgCommitTs(long ov, long nv) {
    return (long) COMMIT_TS.compareAndExchange(this, ov, nv);
  }
}
