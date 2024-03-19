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

package dev.tauri.choam
package internal
package mcas
package emcas

import java.lang.ref.WeakReference
import java.util.concurrent.ThreadLocalRandom

import skiplist.SkipListMap

private[mcas] abstract class GlobalContext
  extends GlobalContextBase
  with Mcas.UnsealedMcas { this: Emcas =>

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
    new SkipListMap[Long, WeakReference[EmcasThreadContext]]

  /** Holds the context for each (active) thread */
  private[this] val threadContextKey =
    new ThreadLocal[EmcasThreadContext]()

  private[this] final def newThreadContext(): EmcasThreadContext =
    new EmcasThreadContext(this, Thread.currentThread().getId())

  /** Gets of creates the context for the current thread */
  private[emcas] final def currentContextInternal(): EmcasThreadContext = {
    // TODO: Try out what happens with JVM 21+ virtual threads;
    // TODO: this should work, but maybe wasteful? Does the
    // TODO: skiplist grows too big? Is cleaning too slow?
    val threadContextKey = this.threadContextKey
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
  private[choam] final override def getRetryStats(): Mcas.RetryStats = {
    // `LongRef`s are still cheaper than using an iterator (tuples):
    @nowarn("cat=lint-performance")
    var commits = 0L
    @nowarn("cat=lint-performance")
    var retries = 0L
    @nowarn("cat=lint-performance")
    var maxRetries = 0L
    this._threadContexts.foreach { (tid, wr) =>
      val tctx = wr.get()
      if (tctx ne null) {
        // The commits and retries values
        // are not necessarily consistent
        // with each other; but these are
        // just stats for informational
        // purposes...
        val stats = tctx.getRetryStats()
        commits += stats.commits
        retries += stats.retries
        if (stats.maxRetries > maxRetries) {
          maxRetries = stats.maxRetries
        }
      } else {
        this._threadContexts.remove(tid, wr) // clean empty weakref
        ()
      }
    }
    Mcas.RetryStats(
      commits = commits,
      retries = retries,
      maxRetries = maxRetries
    )
  }

  private[choam] final override def collectExchangerStats(): Map[Long, Map[AnyRef, AnyRef]] = {
    val mb = Map.newBuilder[Long, Map[AnyRef, AnyRef]]
    this._threadContexts.foreach { (tid, wr) =>
      val tc = wr.get()
      if (tc ne null) {
        mb += ((tid, tc.getStatisticsOpaque()))
      } else {
        this._threadContexts.remove(tid, wr) // clean empty weakref
        ()
      }
    }
    mb.result()
  }

  private[choam] final override def maxReusedWeakRefs(): Int = {
    // An `IntRef` is still cheaper than using an iterator (tuples):
    @nowarn("cat=lint-performance")
    var max = 0
    this._threadContexts.foreach { (tid, wr) =>
      val tc = wr.get()
      if (tc ne null) {
        val n = tc.maxReusedWeakRefs()
        if (n > max) {
          max = n
        }
      } else {
        this._threadContexts.remove(tid, wr) // clean empty weakref
        ()
      }
    }
    max
  }

  /** For testing. */
  private[emcas] final def threadContextExists(threadId: Long): Boolean = {
    // A `BooleanRef` is still cheaper than using an iterator (tuples):
    @nowarn("cat=lint-performance")
    var exists = false
    this._threadContexts.foreach { (tid, wr) =>
      if (wr.get() eq null) {
        this._threadContexts.remove(tid, wr) // clean empty weakref
        ()
      } else if (tid == threadId) {
        exists = true
      }
    }
    exists
  }

  private[emcas] final def threadContextCount(): Int = {
    // Allocating one `IntRef`, and using that
    // is still cheaper than using an iterator,
    // and allocating a tuple on each iteration:
    @nowarn("cat=lint-performance")
    var count = 0
    this._threadContexts.foreach { (tid, wr) =>
      if (wr.get() eq null) {
        this._threadContexts.remove(tid, wr) // clean empty weakref
        ()
      } else {
        count += 1
      }
    }
    count
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
    if (tlr.nextInt(256) == 0) {
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
    val threadContexts = this._threadContexts
    threadContexts.foreach { (tid, wr) =>
      if (wr.get() eq null) {
        threadContexts.remove(tid, wr)
        ()
      }
    }
  }
}
