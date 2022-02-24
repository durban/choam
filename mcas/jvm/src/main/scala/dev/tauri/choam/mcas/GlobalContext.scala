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

package dev.tauri.choam
package mcas

import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentSkipListMap

import scala.jdk.javaapi.CollectionConverters

private final class GlobalContext(impl: Emcas.type) {

  // TODO: should be `private[emcas]`
  private[mcas] val commitTs: MemoryLocation[Long] =
    MemoryLocation.unsafePadded(Version.Start)

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
  private[this] val _threadContexts =
    new ConcurrentSkipListMap[Long, WeakReference[EmcasThreadContext]]
    // TODO: we need to remove empty weakrefs somewhere

  /** Holds the context for each (active) thread */
  private[this] val threadContextKey =
    new ThreadLocal[EmcasThreadContext]()

  private[this] def newThreadContext(): EmcasThreadContext =
    new EmcasThreadContext(this, Thread.currentThread().getId(), impl)

  /** Gets of creates the context for the current thread */
  private[mcas] def currentContext(): EmcasThreadContext = {
    threadContextKey.get() match {
      case null =>
        val tc = this.newThreadContext()
        threadContextKey.set(tc)
        this._threadContexts.put(
          Thread.currentThread().getId(),
          new WeakReference(tc)
        )
        tc
      case tc =>
        tc
    }
  }

  /** Only for testing/benchmarking */
  private[choam] final def getRetryStats(): MCAS.RetryStats = {
    var commits = 0L
    var fullRetries = 0L
    var mcasRetries = 0L
    threadContexts().foreach { tctx =>
      // Calling `getCommitsAndRetries` is not
      // thread-safe here, but we only need these statistics
      // for benchmarking, so we're just hoping for the best...
      val stats = tctx.getRetryStats()
      commits += stats.commits
      fullRetries += stats.fullRetries
      mcasRetries += stats.mcasRetries
    }
    MCAS.RetryStats(
      commits = commits,
      fullRetries = fullRetries,
      mcasRetries = mcasRetries
    )
  }

  /** Only for testing/benchmarking */
  private[choam] def collectExchangerStats(): Map[Long, Map[AnyRef, AnyRef]] = {
    threadContexts().foldLeft(Map.empty[Long, Map[AnyRef, AnyRef]]) { (acc, tc) =>
      acc + (tc.tid -> tc.getStatisticsOpaque())
    }
  }

  /** Only for testing/benchmarking */
  private[choam] final def maxReusedWeakRefs(): Int = {
    threadContexts().foldLeft(0) { (max, tc) =>
      val n = tc.maxReusedWeakRefs()
      if (n > max) n else max
    }
  }

  private[mcas] final def threadContexts(): Iterator[EmcasThreadContext] = {
    val iterWeak = this._threadContexts.values().iterator()
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
