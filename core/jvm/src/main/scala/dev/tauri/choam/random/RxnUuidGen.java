/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.UUID;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import dev.tauri.choam.internal.VarHandleHelper;
import dev.tauri.choam.internal.mcas.Mcas;

// Note: this class/object is duplicated for JVM/JS
final class RxnUuidGen {

  private RxnUuidGen() {}

  private static final long versionNegMask =
    0xffffffffffff0fffL;

  private static final long version =
    0x0000000000004000L;

  private static final long variantNegMask =
    0x3fffffffffffffffL;

  private static final long variant =
    0x8000000000000000L;

  private static final VarHandle BYTE_ARRAY_VIEW;

  static {
    BYTE_ARRAY_VIEW = VarHandleHelper.withInvokeExactBehavior(
      MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN)
    );
  }

  static final UUID unsafeRandomUuidInternal(Mcas.ThreadContext ctx) {
    var buff = new byte[16]; // TODO: don't allocate (use a thread-local buffer)
    ctx.impl().osRng().nextBytes(buff);
    return uuidFromRandomBytesInternal(buff);
  }

  static final UUID uuidFromRandomBytes(byte[] buff) {
    return uuidFromRandomBytesInternal(buff);
  }

  private static final UUID uuidFromRandomBytesInternal(byte[] buff) {
    var msbs = getLongAtP(buff, 0);
    var lsbs = getLongAtP(buff, 8);
    msbs &= versionNegMask;
    msbs |= version;
    lsbs &= variantNegMask;
    lsbs |= variant;
    return new UUID(msbs, lsbs);
  }

  private static final long getLongAtP(byte[] arr, int offset) {
    return (long) BYTE_ARRAY_VIEW.get(arr, offset);
  }
}
