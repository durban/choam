/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentSkipListMap

import scala.jdk.javaapi.CollectionConverters

private final class GlobalContext(impl: EMCAS.type) {

  /**
   * `ThreadContext`s of all the (active) threads
   *
   * Threads hold a strong reference to their
   * `ThreadContext` in a thread local. Thus,
   * we only need a weakref here. If a thread
   * dies, its thread locals are cleared, so
   * the context can be GC'd (by the JVM).
   *
   * Removing a dead thread's context will not
   * affect safety, because a dead thread will never
   * continue its current op (if any).
   */
  private[this] val threadContexts =
    new ConcurrentSkipListMap[Long, WeakReference[ThreadContext]]
    // TODO: we need to remove empty weakrefs somewhere

  /** Holds the context for each (active) thread */
  private[this] val threadContextKey =
    new ThreadLocal[ThreadContext]()

  private[this] def newThreadContext(): ThreadContext =
    new ThreadContext(this, Thread.currentThread().getId(), impl)

  /** Gets of creates the context for the current thread */
  private[mcas] def threadContext(): ThreadContext = {
    threadContextKey.get() match {
      case null =>
        val tc = this.newThreadContext()
        threadContextKey.set(tc)
        this.threadContexts.put(
          Thread.currentThread().getId(),
          new WeakReference(tc)
        )
        tc
      case tc =>
        tc
    }
  }

  /** Only for testing/benchmarking */
  private[choam] final def countCommitsAndRetries(): (Long, Long) = {
    var commits = 0L
    var retries = 0L
    threadContexts().foreach { tctx =>
      // Calling `getCommitsAndRetries` is not
      // thread-safe here, but we only need these statistics
      // for benchmarking, so we're just hoping for the best...
      val (c, r) = tctx.getCommitsAndRetries()
      commits += c.toLong
      retries += r.toLong
    }
    (commits, retries)
  }

  private[choam] final def threadContexts(): Iterator[ThreadContext] = {
    val iterWeak = this.threadContexts.values().iterator()
    CollectionConverters.asScala(iterWeak).flatMap { weakref =>
      weakref.get() match {
        case null =>
          Iterator.empty
        case tctx =>
          Iterator.single(tctx)
      }
    }
  }
}
