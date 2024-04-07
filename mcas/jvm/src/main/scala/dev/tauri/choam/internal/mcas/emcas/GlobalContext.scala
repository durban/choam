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

import cats.kernel.Order

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
    new SkipListMap[GlobalContext.TCtxWeakRef, Unit]

  /** Holds the context for each (active) thread */
  private[this] val threadContextKey =
    new ThreadLocal[EmcasThreadContext]()

  private[this] final def newThreadContext(): EmcasThreadContext =
    new EmcasThreadContext(this, Thread.currentThread().getId())

  /** Gets or creates the context for the current thread */
  private[emcas] final def currentContextInternal(): EmcasThreadContext = {
    val threadContextKey = this.threadContextKey
    threadContextKey.get() match {
      case null =>
        // slow path: need to create new ctx
        val tc = this.newThreadContext()
        threadContextKey.set(tc)
        val currThread = Thread.currentThread()
        if (McasStatus.statsEnabled) {
          val wr = new GlobalContext.TCtxWeakRef(currThread.getId(), tc)
          this._threadContexts.put(
            wr,
            ()
          ) : Unit // don't care the old ctx, it's for a terminated thread (and the TID was reused)
          this.maybeGcThreadContexts(this.getAndIncrThreadCtxCount() + 1L) // we might also clear weakrefs
        }
        tc
      case tc =>
        // "fast" path: ctx already exists
        tc
    }
  }

  /** Only for testing/benchmarking/JMX */
  private[choam] final override def getRetryStats(): Mcas.RetryStats = {
    // allocating this builder is still cheaper than using an iterator (tuples):
    val b = new GlobalContext.StatsBuilder()
    this._threadContexts.foreachAndSum { (wr, _) =>
      val tctx = wr.get()
      if (tctx ne null) {
        b += tctx.getRetryStats()
      } else {
        this._threadContexts.del(wr) : Unit // clean empty weakref
      }
      0
    } : Unit
    b.build()
  }

  private[choam] final override def collectExchangerStats(): Map[Long, Map[AnyRef, AnyRef]] = {
    val mb = Map.newBuilder[Long, Map[AnyRef, AnyRef]]
    this._threadContexts.foreachAndSum { (wr, _) =>
      val tc = wr.get()
      if (tc ne null) {
        mb += ((wr.tid, tc.getStatisticsO()))
      } else {
        this._threadContexts.del(wr) : Unit // clean empty weakref
      }
      0
    } : Unit
    mb.result()
  }

  private[choam] final override def maxReusedWeakRefs(): Int = {
    // An `IntRef` is still cheaper than using an iterator (tuples):
    @nowarn("cat=lint-performance")
    var max = 0
    this._threadContexts.foreachAndSum { (wr, _) =>
      val tc = wr.get()
      if (tc ne null) {
        val n = tc.maxReusedWeakRefs()
        if (n > max) {
          max = n
        }
      } else {
        this._threadContexts.del(wr) : Unit // clean empty weakref
      }
      0
    } : Unit
    max
  }

  /** For testing. */
  private[emcas] final def threadContextExists(threadId: Long): Boolean = {
    val sum = this._threadContexts.foreachAndSum { (wr, _) =>
      if (wr.get() eq null) {
        this._threadContexts.del(wr) : Unit // clean empty weakref
        0
      } else if (wr.tid == threadId) {
        1
      } else {
        0
      }
    }

    (sum != 0)
  }

  private[emcas] final def threadContextCount(): Int = {
    this._threadContexts.foreachAndSum { (wr, _) =>
      if (wr.get() eq null) {
        this._threadContexts.del(wr) : Unit // clean empty weakref
        0
      } else {
        1
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
   * to traverse the whole skip-list). During typical
   * usage (i.e., a thread-pool) the actual cleanup
   * (`gcThreadContexts`) should almost never be called.
   */
  private[this] final def maybeGcThreadContexts(n: Long): Unit = {
    if ((n & (n - 1L)) == 0L) { // power of 2
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
    val sum = threadContexts.foreachAndSum { (wr, _) =>
      if (wr.get() eq null) {
        threadContexts.del(wr) : Unit
        -1
      } else {
        0
      }
    }
    this.getAndAddThreadCtxCount(sum.toLong) : Unit
  }
}

private object GlobalContext {

  private final class TCtxWeakRef(
    val tid: Long,
    ctx: EmcasThreadContext
  ) extends WeakReference[EmcasThreadContext](ctx)

  private final object TCtxWeakRef {
    implicit val orderInstance: Order[TCtxWeakRef] = { (wr1, wr2) =>
      java.lang.Long.compare(wr1.tid, wr2.tid)
    }
  }

  private final class StatsBuilder {

    private[this] var commits: Long = 0L
    private[this] var retries: Long = 0L
    private[this] var extensions: Long = 0L
    private[this] var mcasAttempts: Long = 0L
    private[this] var committedRefs: Long = 0L
    private[this] var cyclesDetected: Long = 0
    private[this] var maxRetries: Long = 0L
    private[this] var maxCommittedRefs: Int = 0
    private[this] var maxBloomFilterSize: Int = 0

    final def += (stats: Mcas.RetryStats): this.type = {
      // The commits and retries values
      // are not necessarily consistent
      // with each other; but these are
      // just stats for informational
      // purposes...
      this.commits += stats.commits
      this.retries += stats.retries
      this.extensions += stats.extensions
      this.mcasAttempts += stats.mcasAttempts
      this.committedRefs += stats.committedRefs
      this.cyclesDetected += stats.cyclesDetected
      if (stats.maxRetries > this.maxRetries) {
        this.maxRetries = stats.maxRetries
      }
      if (stats.maxCommittedRefs > this.maxCommittedRefs) {
        this.maxCommittedRefs = stats.maxCommittedRefs
      }
      if (stats.maxBloomFilterSize > this.maxBloomFilterSize) {
        this.maxBloomFilterSize = stats.maxBloomFilterSize
      }

      this
    }

    final def build(): Mcas.RetryStats = {
      Mcas.RetryStats(
        commits = this.commits,
        retries = this.retries,
        extensions = this.extensions,
        mcasAttempts = this.mcasAttempts,
        committedRefs = this.committedRefs,
        cyclesDetected = this.cyclesDetected,
        maxRetries = this.maxRetries,
        maxCommittedRefs = this.maxCommittedRefs,
        maxBloomFilterSize = this.maxBloomFilterSize,
      )
    }
  }
}
