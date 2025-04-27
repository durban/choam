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

package dev.tauri.choam
package bench
package rxn

import org.openjdk.jmh.annotations._

import async.Promise

import util._

/**
 * Benchmarks to determine whether `Rxn` being an arrow (i.e.,
 * `Rxn[-A, +B]`) has performance advantages in realistic situations
 * over just being a monad (i.e., a hypothetical `Rxn[+B]`).
 *
 * The main functionality we get from having an arrow is directly
 * "piping" the result of one `Rxn` into another (with `>>>`). That is,
 * we can do this:
 *
 * ```
 * val rxn1: Rxn[A, B] = ...
 * val rxn2: Rxn[B, C] = ...
 * rxn1 >>> rxn2
 * ```
 *
 * instead of the obvious monad-like composition:
 *
 * ```
 * val rxn1: Axn[B] = ...
 * def rxn2(b: B): Axn[C] = ...
 * rxn1.flatMapF(b => rxn2(b))
 * ```
 *
 * In theory, by using `>>>` we avoid at least a closure allocation.
 * But does that matters in semi-realistic situations? That's the
 * question that this benchmark tries to answer.
 */
@Fork(3)
@Threads(2)
class ArrowBench {

  import ArrowBench.{ SharedSt, ThreadSt, N }

  @Benchmark
  def arrow(s: SharedSt, k: ThreadSt, r: RandomState): Boolean = {
    val ref = s.refs(r.nextIntBounded(N))
    s.replaceAndCompleteWithArrow(ref, "foo").unsafePerformInternal0(null, k.mcasCtx)
  }

  @Benchmark
  def monad(s: SharedSt, k: ThreadSt, r: RandomState): Boolean = {
    val ref = s.refs(r.nextIntBounded(N))
    s.replaceAndCompleteWithMonad(ref, "foo").unsafePerformInternal0(null, k.mcasCtx)
  }
}

object ArrowBench {

  final val N = 32

  @State(Scope.Thread)
  class ThreadSt extends McasImplState

  @State(Scope.Benchmark)
  class SharedSt extends McasImplStateBase {

    val refs: Array[Ref[Promise[String]]] = Array.fill(N) {
      Ref.unsafePadded[Promise[String]](
        null : Promise[String],
        this.mcasImpl.currentContext().refIdGen,
      )
    }

    final def replaceAndCompleteWithArrow(ref: Ref[Promise[String]], str: String): Rxn[Any, Boolean] = {
      Promise[String] >>> ref.getAndSet.flatMapF { ov =>
        if (ov ne null) ov.complete1(str) else Rxn.pure(true)
      }
    }

    final def replaceAndCompleteWithMonad(ref: Ref[Promise[String]], str: String): Rxn[Any, Boolean] = {
      Promise[String].flatMapF { p =>
        ref.getAndUpdate { _ => p }.flatMapF { ov =>
          if (ov ne null) ov.complete1(str) else Rxn.pure(true)
        }
      }
    }
  }
}
