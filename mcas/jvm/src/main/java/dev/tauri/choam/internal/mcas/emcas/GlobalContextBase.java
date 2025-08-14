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

package dev.tauri.choam.internal.mcas.emcas;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.ref.Reference;

import dev.tauri.choam.internal.VarHandleHelper;
import dev.tauri.choam.internal.mcas.VersionJ;
import dev.tauri.choam.internal.mcas.PaddedMemoryLocationPadding;

abstract class GlobalContextBase extends PaddedMemoryLocationPadding {

  private static final VarHandle COMMIT_TS;
  private static final VarHandle THREAD_CTX_COUNT;
  private static final MethodHandle IS_VIRTUAL;

  static final String emcasJmxStatsNamePrefix =
    "dev.tauri.choam.stats:type=EmcasJmxStats";

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      COMMIT_TS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(GlobalContextBase.class, "commitTs", long.class));
      THREAD_CTX_COUNT = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(GlobalContextBase.class, "threadCtxCount", long.class));
      MethodHandle mh;
      try {
        mh = l.findVirtual(Thread.class, "isVirtual", MethodType.methodType(boolean.class));
      } catch (NoSuchMethodException e) {
        // fallback to constant false:
        mh = MethodHandles.dropArguments(MethodHandles.constant(boolean.class, false), 0, Thread.class);
      }
      IS_VIRTUAL = mh;
    } catch (ReflectiveOperationException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  final static boolean isVirtualThread(Thread t) {
    try {
      return (boolean) IS_VIRTUAL.invokeExact(t);
    } catch (Throwable e) {
      return false; // whatever...
    }
  }

  private volatile long commitTs = VersionJ.Start;
  // TODO: add padding between commitTs and threadCtxCount
  private volatile long threadCtxCount;

  final long getCommitTs() {
    return this.commitTs; // volatile
  }

  final long cmpxchgCommitTs(long ov, long nv) {
    return (long) COMMIT_TS.compareAndExchange(this, ov, nv);
  }

  final long getAndIncrThreadCtxCount() {
    return this.getAndAddThreadCtxCount(1L);
  }

  final long getAndAddThreadCtxCount(long x) {
    return (long) THREAD_CTX_COUNT.getAndAddAcquire(this, x);
  }

  final void reachabilityFence(Object ref) {
    Reference.reachabilityFence(ref);
  }

  protected final static String registerEmcasJmxStats(Emcas emcas) throws javax.management.JMException {
    String objNameStr = String.format("%s-%08x", emcasJmxStatsNamePrefix, System.identityHashCode(emcas));
    var objName = new javax.management.ObjectName(objNameStr);
    var ejs = new EmcasJmxStats(emcas);
    java.lang.management.ManagementFactory.getPlatformMBeanServer().registerMBean(ejs, objName);
    return objNameStr;
  }

  protected final static void unregisterEmcasJmxStats(String objNameStr) throws javax.management.JMException {
    if (objNameStr == null) {
      // in theory this is impossible,
      // but it's too late, as we're
      // in `close`; and we don't
      // really want to throw during
      // resource release anyway
    } else {
      java.lang.management.ManagementFactory.getPlatformMBeanServer().unregisterMBean(
        new javax.management.ObjectName(objNameStr)
      );
    }
  }
}
