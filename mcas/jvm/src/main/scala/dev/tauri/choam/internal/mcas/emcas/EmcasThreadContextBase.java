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

import scala.collection.immutable.Map;
import scala.collection.immutable.Map$;

import dev.tauri.choam.internal.VarHandleHelper;

abstract class EmcasThreadContextBase {

  private static final VarHandle COMMITS;
  private static final VarHandle FULL_RETRIES;
  private static final VarHandle MCAS_RETRIES;
  private static final VarHandle MAX_REUSE_EVER;
  private static final VarHandle STATISTICS;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      COMMITS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_commits", long.class));
      FULL_RETRIES = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_fullRetries", long.class));
      MCAS_RETRIES = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_mcasRetries", long.class));
      MAX_REUSE_EVER = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_maxReuseEver", int.class));
      STATISTICS = VarHandleHelper.withInvokeExactBehavior(l.findVarHandle(EmcasThreadContextBase.class, "_statistics", Map.class));
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  // intentionally non-volatile, see below
  private long _commits; // = 0L
  private long _fullRetries; // = 0L
  private long _mcasRetries; // = 0L
  private int _maxReuseEver; // = 0
  private Map<Object, Object> _statistics = Map$.MODULE$.empty();

  protected long getCommitsOpaque() {
    return (long) COMMITS.getOpaque(this);
  }

  protected long getFullRetriesOpaque() {
    return (long) FULL_RETRIES.getOpaque(this);
  }

  protected long getMcasRetriesOpaque() {
    return (long) MCAS_RETRIES.getOpaque(this);
  }

  protected void recordCommitPlain(int fullRetries, int mcasRetries) {
    this._commits += 1L;
    this._fullRetries += (long) fullRetries;
    this._mcasRetries += (long) mcasRetries;
  }

  protected int getMaxReuseEverPlain() {
    return this._maxReuseEver;
  }

  protected int getMaxReuseEverOpaque() {
    return (int) MAX_REUSE_EVER.getOpaque(this);
  }

  protected void setMaxReuseEverPlain(int nv) {
    this._maxReuseEver = nv;
  }

  // TODO: this is a hack, should have a proper type
  protected Map<Object, Object> _getStatisticsPlain() {
    return this._statistics;
  }

  protected Map<Object, Object> _getStatisticsOpaque() {
    return (Map<Object, Object>) STATISTICS.getOpaque(this);
  }

  protected void _setStatisticsPlain(Map<Object, Object> nv) {
    this._statistics = nv;
  }
}
