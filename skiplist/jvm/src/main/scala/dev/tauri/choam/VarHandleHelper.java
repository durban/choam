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

// TODO: should be internal (at least the package name)
public final class VarHandleHelper {

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
          VarHandleHelper.class,
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

  /**
   * If the `VarHandle#withInvokeExactBehavior` method exists
   * on the current JVM, calls that method on `vh` and returns
   * its result. Otherwise returns `vh`.
   *
   * This method is intended as a best effort approximation of
   * the "real" `withInvokeExactBehavior` method, while remaining
   * compatible with older JVMs.
   *
   * @param vh the `VarHandle` to call `withInvokeExactBehavior` on
   *           (if possible).
   * @return the result of `withInvokeExactBehavior`, or the
   *         unchanged `vh` (if the method doesn't exist).
   */
  public static final VarHandle withInvokeExactBehavior(VarHandle vh) {
    try {
      return (VarHandle) WITH_INVOKE_EXACT_BEHAVIOR.invokeExact(vh);
    } catch (Throwable t) {
      // In reality, this method won't throw any
      // checked exceptions, but we must catch
      // `Throwable` due to the signature of
      // `invokeExact`. We just wrap it in an
      // `ExceptionInInitializerError`, as we
      // should only be called in a static
      // initializer anyway.
      throw new ExceptionInInitializerError(t);
    }
  }

  /** This is just some static methods, don't instantiate it */
  private VarHandleHelper() {}
}
