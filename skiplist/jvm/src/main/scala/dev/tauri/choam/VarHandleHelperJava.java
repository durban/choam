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

package dev.tauri.choam;

import java.util.Collections;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;

public final class VarHandleHelperJava {

  private static final MethodHandle WITH_INVOKE_EXACT_BEHAVIOR;

  static {
    WITH_INVOKE_EXACT_BEHAVIOR = findHandle();
  }

  private static final MethodHandle findHandle() {
    MethodHandles.Lookup l = MethodHandles.lookup();
    try {
      return l.findVirtual(
        VarHandle.class,
        "withInvokeExactBehavior",
        MethodType.methodType(VarHandle.class, Collections.emptyList())
      );
    } catch (NoSuchMethodException e) {
      try {
        return l.findStatic(
          VarHandleHelperJava.class,
          "noopFallback",
          MethodType.methodType(VarHandle.class, new Class<?>[] { VarHandle.class })
        );
      } catch (ReflectiveOperationException e2) {
        var ex = new ExceptionInInitializerError(e2);
        ex.addSuppressed(e);
        throw ex;
      }
    } catch (IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final VarHandle noopFallback(VarHandle vh) {
    return vh;
  }

  // TODO: `throws Throwable` is really inconvenient
  public static final VarHandle withInvokeExactBehavior(VarHandle vh) throws Throwable {
    return (VarHandle) WITH_INVOKE_EXACT_BEHAVIOR.invokeExact(vh);
  }

  /** This is just some static methods, don't instantiate it */
  private VarHandleHelperJava() {}
}
