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

private final class GlobalContext(impl: EMCAS.type)
  extends IBR[ThreadContext](Long.MinValue) {

  override def newThreadContext(): ThreadContext =
    new ThreadContext(this, Thread.currentThread().getId(), impl)

  /** Only for testing/benchmarking */
  private[choam] final def countFinalizedDescriptors(): (Long, Long) = {
    var countDescrs = 0L
    var countMaxDescrs = 0L
    threadContexts().foreach { tctx =>
      // Calling `getFinalizedDescriptorsCount` is not
      // thread-safe here, but we only need these statistics
      // for benchmarking, so we're just hoping for the best...
      countDescrs += tctx.getFinalizedDescriptorsCount().toLong
      countMaxDescrs += tctx.getMaxFinalizedDescriptorsCount().toLong
    }

    (countDescrs, countMaxDescrs)
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

  /** Only for testing/benchmarking */
  private[choam] def collectExchangerStats(): Map[Long, Map[AnyRef, AnyRef]] = {
    threadContexts().foldLeft(Map.empty[Long, Map[AnyRef, AnyRef]]) { (acc, tc) =>
      acc + (tc.tid -> tc.getStatistics())
    }
  }

  private[this] final def threadContexts(): Iterator[ThreadContext] = {
    val iterWeak = this.snapshotReservations.valuesIterator
    iterWeak.flatMap { weakref =>
      weakref.get() match {
        case null =>
          Iterator.empty
        case tctx =>
          Iterator.single(tctx)
      }
    }
  }
}
