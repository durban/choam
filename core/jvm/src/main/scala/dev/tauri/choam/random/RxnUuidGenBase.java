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

package dev.tauri.choam.random;


import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import dev.tauri.choam.vhandle.VarHandleHelper;

abstract class RxnUuidGenBase {
  private static final VarHandle BYTE_ARRAY_VIEW;

  static {
    BYTE_ARRAY_VIEW = VarHandleHelper.withInvokeExactBehavior(
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN)
    );
  }

  protected final long getLongAt(byte[] arr, int offset) {
    return (long) BYTE_ARRAY_VIEW.get(arr, offset);
  }
}
