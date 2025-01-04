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

import scala.collection.immutable.Map;
import scala.collection.immutable.Map$;

import dev.tauri.choam.internal.VarHandleHelper;

abstract class EmcasThreadContextBase {

  private static final VarHandle COMMITS;
  private static final VarHandle RETRIES;
  private static final VarHandle EXTENSIONS;
  private static final VarHandle MCAS_ATTEMPTS;
  private static final VarHandle COMMITTED_REFS;
  private static final VarHandle CYCLES_DETECTED;
  private static final VarHandle MAX_REUSE_EVER;
  private static final VarHandle MAX_RETRIES_EVER;
  private static final VarHandle MAX_COMMITTED_REFS_EVER;
  private static final VarHandle MAX_BLOOM_FILTER_SIZE;
  private static final VarHandle STATISTICS;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      COMMITS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_commits", long.class));
      RETRIES = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_retries", long.class));
      EXTENSIONS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_extensions", long.class));
      MCAS_ATTEMPTS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_mcasAttempts", long.class));
      COMMITTED_REFS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_committedRefs", long.class));
      CYCLES_DETECTED = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_cyclesDetected", int.class));
      MAX_REUSE_EVER = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_maxReuseEver", int.class));
      MAX_RETRIES_EVER = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_maxRetriesEver", long.class));
      MAX_COMMITTED_REFS_EVER = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_maxCommittedRefsEver", int.class));
      MAX_BLOOM_FILTER_SIZE = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_maxBloomFilterSize", int.class));
      STATISTICS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_statistics", Map.class));
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  // intentionally non-volatile, see below
  private long _commits; // = 0L
  private long _retries; // = 0L
  private long _extensions; // = 0L
  private long _mcasAttempts; // = 0L
  private long _committedRefs; // = 0L
  private int _cyclesDetected; // = 0
  private int _maxReuseEver; // = 0
  private long _maxRetriesEver; // = 0L
  private int _maxCommittedRefsEver; // = 0
  private int _maxBloomFilterSize; // = 0
  private Map<Object, Object> _statistics = Map$.MODULE$.empty();

  protected long getCommitsO() {
    return (long) COMMITS.getOpaque(this);
  }

  protected long getRetriesO() {
    return (long) RETRIES.getOpaque(this);
  }

  protected long getExtensionsO() {
    return (long) EXTENSIONS.getOpaque(this);
  }

  protected long getMcasAttemptsO() {
    return (long) MCAS_ATTEMPTS.getOpaque(this);
  }

  protected long getCommittedRefsO() {
    return (long) COMMITTED_REFS.getOpaque(this);
  }

  protected int getCyclesDetectedO() {
    return (int) CYCLES_DETECTED.getOpaque(this);
  }

  protected long getMaxRetriesO() {
    return (long) MAX_RETRIES_EVER.getOpaque(this);
  }

  protected int getMaxCommittedRefsO() {
    return (int) MAX_COMMITTED_REFS_EVER.getOpaque(this);
  }

  protected int getMaxBloomFilterSizeO() {
    return (int) MAX_BLOOM_FILTER_SIZE.getOpaque(this);
  }

  protected void recordEmcasFinalizedO() {
    MCAS_ATTEMPTS.setOpaque(this, this._mcasAttempts + 1L);
  }

  protected void recordCommitO(int retries, int committedRefs, int descExtensions) {
    // Only one thread writes, so `+=`-like
    // increment is fine here. There is a
    // race though: a reader can read values
    // of commits and retries which do not
    // "belong" together (e.g., a current
    // value for commits, and an old one
    // for retries). But this is just for
    // statistical and informational purposes,
    // so we don't really care.
    COMMITS.setOpaque(this, this._commits + 1L);
    long retr = (long) retries;
    RETRIES.setOpaque(this, this._retries + retr);
    EXTENSIONS.setOpaque(this, this._extensions + descExtensions);
    COMMITTED_REFS.setOpaque(this, this._committedRefs + ((long) committedRefs));
    if (retr > this._maxRetriesEver) {
      MAX_RETRIES_EVER.setOpaque(this, retr);
    }
    if (committedRefs > this._maxCommittedRefsEver) {
      MAX_COMMITTED_REFS_EVER.setOpaque(this, committedRefs);
    }
  }

  protected void recordCycleDetectedO(int bloomFilterSize) {
    CYCLES_DETECTED.setOpaque(this, this._cyclesDetected + 1);
    if (bloomFilterSize > this._maxBloomFilterSize) {
      MAX_BLOOM_FILTER_SIZE.setOpaque(this, bloomFilterSize);
    }
  }

  protected int getMaxReuseEverP() {
    return this._maxReuseEver;
  }

  protected int getMaxReuseEverO() {
    return (int) MAX_REUSE_EVER.getOpaque(this);
  }

  protected void setMaxReuseEverP(int nv) {
    this._maxReuseEver = nv;
  }

  // TODO: this is a hack, should have a proper type
  protected Map<Object, Object> _getStatisticsP() {
    return this._statistics;
  }

  protected Map<Object, Object> _getStatisticsO() {
    return (Map<Object, Object>) STATISTICS.getOpaque(this);
  }

  protected void _setStatisticsP(Map<Object, Object> nv) {
    this._statistics = nv;
  }
}
