/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam
package kcas

import java.util.concurrent.ConcurrentSkipListMap

final class GlobalContext {

  private[this] val threadLocal =
    new ThreadLocal[ThreadContext]

  private[this] val threadContexts =
    new ConcurrentSkipListMap[Long, ThreadContext]

  def currentContext(): ThreadContext = {
    this.threadLocal.get() match {
      case null =>
        val tid = Thread.currentThread().getId()
        val ctx = this.threadContexts.get(tid) match {
          case null =>
            val ctx = new ThreadContext(this, tid)
            this.threadContexts.putIfAbsent(tid, ctx) match {
              case null => ctx // OK
              case _ => impossible(s"concurrent modification for thread ${tid}")
            }
          case old =>
            val ctx = old.copy()
            this.threadContexts.putIfAbsent(tid, ctx) match {
              case null => impossible(s"concurrent modification for thread ${tid}")
              case oldCtx if (oldCtx eq old) => ctx // OK
              case _ => impossible(s"concurrent modification for thread ${tid}")
            }
        }
        this.threadLocal.set(ctx)
        ctx
      case ctx =>
        ctx
    }
  }
}

final class ThreadContext(global: GlobalContext, val tid: Long) {

  /** Note: should read any data with 'acquire'. */
  final def copy(): ThreadContext =
    new ThreadContext(this.global, this.tid)
}
