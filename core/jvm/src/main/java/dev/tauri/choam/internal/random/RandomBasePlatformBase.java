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

package dev.tauri.choam.internal.random;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import dev.tauri.choam.internal.VarHandleHelper;

abstract class RandomBasePlatformBase {

  private static final VarHandle BYTE_ARRAY_VIEW;

  static {
    BYTE_ARRAY_VIEW = VarHandleHelper.withInvokeExactBehavior(
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN)
    );
  }

  final long getLongAt0P(byte[] arr) {
    return (long) BYTE_ARRAY_VIEW.get(arr, 0);
  }

  final void putLongAtIdxP(byte[] arr, int idx, long nv) {
    BYTE_ARRAY_VIEW.set(arr, idx, nv);
  }
}
