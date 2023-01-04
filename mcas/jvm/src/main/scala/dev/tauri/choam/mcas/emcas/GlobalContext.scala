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

package dev.tauri.choam
package mcas
package emcas

import java.lang.ref.WeakReference
import java.util.concurrent.{ ConcurrentSkipListMap, ThreadLocalRandom }

import scala.collection.AbstractIterator

private final class GlobalContext(impl: Emcas.type)
  extends GlobalContextBase { self =>

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
  private[this] val _threadContexts = {
    // TODO: Technically `ConcurrentSkipListMap` is
    // TODO: not lock-free, because it maintains its
    // TODO: size in a `LongAdder`, which has a
    // TODO: slow-path protected by a spinlock.
    // TODO: (We usually expect low contention on this
    // TODO: `ConcurrentSkipListMap`, so it should
    // TODO: rarely hit this slow-path, if ever.)
    new ConcurrentSkipListMap[Long, WeakReference[EmcasThreadContext]]
  }

  /** Holds the context for each (active) thread */
  private[this] val threadContextKey =
    new ThreadLocal[EmcasThreadContext]()

  private[this] def newThreadContext(): EmcasThreadContext =
    new EmcasThreadContext(this, Thread.currentThread().getId(), impl)

  /** Gets of creates the context for the current thread */
  private[emcas] def currentContext(): EmcasThreadContext = {
    threadContextKey.get() match {
      case null =>
        // slow path: need to create new ctx
        val tc = this.newThreadContext()
        threadContextKey.set(tc)
        this._threadContexts.put(
          Thread.currentThread().getId(),
          new WeakReference(tc)
        )
        this.maybeGcThreadContexts(tc.random) // we might also clear weakrefs
        tc
      case tc =>
        // "fast" path: ctx already exists
        tc
    }
  }

  /** Only for testing/benchmarking */
  private[choam] final def getRetryStats(): Mcas.RetryStats = {
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
    Mcas.RetryStats(
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

  private[emcas] final def threadContexts(): Iterator[EmcasThreadContext] = {
    val iterWeak = this._threadContexts.values().iterator()
    new AbstractIterator[EmcasThreadContext] {

      private[this] val _underlying = iterWeak

      private[this] var _next: EmcasThreadContext = null

      final override def hasNext: Boolean =
        fetchNext()

      @tailrec
      private[this] final def fetchNext(): Boolean = {
        if (_next ne null) {
          true
        } else if (_underlying.hasNext()) {
          _next = _underlying.next().get()
          if (_next eq null) {
            _underlying.remove() // remove weakref from _threadContexts
          }
          fetchNext()
        } else {
          false
        }
      }

      final override def next(): EmcasThreadContext = {
        val r = _next
        _next = null
        if (r eq null) {
          throw new IllegalStateException
        }
        r
      }
    }
  }

  /**
   * We occasionally clean the empty weakrefs from
   * `_threadContexts`, to catch the case when a lot
   * of threads (and `ThreadContexts`) are created,
   * then discarded. (In this case we could hold on
   * to an unbounded number of empty weakrefs.) We
   * don't want to do this often (because we need
   * to traverse the whole skip-list), so we do it
   * approximately once every 256 `ThreadContext`
   * creation. During typical usage (i.e., a thread-pool)
   * the actual cleanup (`gcThreadContexts`) should
   * almost never be called.
   */
  private[this] final def maybeGcThreadContexts(tlr: ThreadLocalRandom): Unit = {
    if (
      (tlr.nextInt(256) == 0) &&
      (_threadContexts.size() > Runtime.getRuntime().availableProcessors())
    ) {
      gcThreadContexts()
    }
  }

  /**
   * Unfortunately we have to traverse the whole
   * skip-list to remove empty weakrefs. (We could
   * use a `ReferenceQueue` like `WeakHashMap` does,
   * but `ReferenceQueue` locks a lot, so we avoid
   * it.)
   */
  private[this] final def gcThreadContexts(): Unit = {
    val it = this._threadContexts.values().iterator()
    while (it.hasNext()) {
      if (it.next().get() eq null) {
        it.remove()
      }
    }
  }

  /** For testing */
  private[emcas] final def size: Int = {
    this._threadContexts.size()
  }
}
