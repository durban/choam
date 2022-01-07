/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam.mcas;

import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

import scala.collection.immutable.Map;
import scala.collection.immutable.Map$;

abstract class EMCASThreadContextBase {

  private static final VarHandle COMMITS;
  private static final VarHandle RETRIES;
  private static final VarHandle MAX_REUSE_EVER;
  private static final VarHandle STATISTICS;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      COMMITS = l.findVarHandle(EMCASThreadContextBase.class, "_commits", int.class);
      RETRIES = l.findVarHandle(EMCASThreadContextBase.class, "_retries", int.class);
      MAX_REUSE_EVER = l.findVarHandle(EMCASThreadContextBase.class, "_maxReuseEver", int.class);
      STATISTICS = l.findVarHandle(EMCASThreadContextBase.class, "_statistics", Map.class);
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  // intentionally non-volatile, see below
  private int _commits; // = 0
  private int _retries; // = 0
  private int _maxReuseEver; // = 0
  private Map<Object, Object> _statistics = Map$.MODULE$.empty();

  protected int getCommitsOpaque() {
    return (int) COMMITS.getOpaque(this);
  }

  protected int getRetriesOpaque() {
    return (int) RETRIES.getOpaque(this);
  }

  protected void recordCommitPlain(int retries) {
    this._commits += 1;
    this._retries += retries; // TODO: overflow
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
