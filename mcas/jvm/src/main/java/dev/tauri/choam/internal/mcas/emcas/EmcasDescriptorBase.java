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

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

import dev.tauri.choam.internal.VarHandleHelper;
import dev.tauri.choam.internal.mcas.VersionJ;
import dev.tauri.choam.internal.mcas.WdLike;

abstract class EmcasDescriptorBase {

  private static final VarHandle STATUS;
  private static final VarHandle WORDS;
  private static final VarHandle WORDS_ARR;
  private static final VarHandle FALLBACK;

  static final Object CLEARED =
    new Object();

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      STATUS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasDescriptorBase.class, "_status", long.class));
      WORDS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasDescriptorBase.class, "_words", WdLike[].class));
      WORDS_ARR = VarHandleHelper.withInvokeExactBehavior(MethodHandles.arrayElementVarHandle(WdLike[].class));
      FALLBACK = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasDescriptorBase.class, "_fallback", EmcasDescriptor.class));
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  // TODO: padding (although, it might increase GC pressure?)
  private volatile long _status =
    VersionJ.Active;

  private volatile WdLike<?>[] _words; // = null

  private volatile EmcasDescriptor _fallback; // = null

  /**
   * @see [[EmcasStatus]]
   */
  final long getStatusV() {
    return this._status; // volatile
  }

  final long getStatusA() {
    return (long) STATUS.getAcquire(this);
  }

  final long cmpxchgStatus(long ov, long nv) {
    return (long) STATUS.compareAndExchange(this, ov, nv);
  }

  final WdLike<?>[] getWordsP() {
    return (WdLike<?>[]) WORDS.get(this);
  }

  final WdLike<?>[] getWordsO() {
    return (WdLike<?>[]) WORDS.getOpaque(this);
  }

  final void setWordsO(WdLike<?>[] words) {
    WORDS.setOpaque(this, words);
  }

  final EmcasDescriptor getFallbackA() {
    return (EmcasDescriptor) FALLBACK.getAcquire(this);
  }

  final EmcasDescriptor cmpxchgFallbackA(EmcasDescriptor ov, EmcasDescriptor nv) {
    return (EmcasDescriptor) FALLBACK.compareAndExchangeAcquire(this, ov, nv);
  }

  final EmcasDescriptor getOrInitFallback(EmcasDescriptor candidate) {
    EmcasDescriptor wit = (EmcasDescriptor) FALLBACK.compareAndExchangeRelease(this, (EmcasDescriptor) null, candidate);
    if (wit == null) {
      return candidate;
    } else {
      // what we want here is a cmpxchg which has
      // Release semantics on success, and Acquire
      // semantics on failure (to get the witness);
      // `VarHandle` has no method for that, so we
      // approximate it with the compareAndExchangeRelease
      // above, and a fence here:
      VarHandle.acquireFence();
      return wit;
    }
  }

  final void wasFinalized(boolean wasSuccessful) {
    // We can clean up to help the GC
    // (even if we have to retry with
    // the fallback, we already copied
    // the array). We're the only ones
    // cleaning up, so `_words` is never
    // `null` here:
    WdLike<?>[] words = this.getWordsO();
    int len = words.length;
    VarHandle.releaseFence();
    this.setWordsO(null);
    // We also set every array element to `null`.
    // This way concurrent helpers, which already
    // have the array in hand, have a chance to
    // realize they should stop. They're doing
    // unsynchronized reads, so it's not guaranteed
    // that they'll see the `null`s, but it's possible.
    // (We might also help the GC with this?)
    Object sentinel = EmcasDescriptorBase.CLEARED;
    for (int idx = 0; idx < len; idx++) {
      @SuppressWarnings("unchecked")
      WdLike<Object> wd = (WdLike<Object>) words[idx];
      WORDS_ARR.setOpaque(words, idx, (WdLike<?>) null);
      wd.wasFinalized(wasSuccessful, sentinel);
    }
  }
}
