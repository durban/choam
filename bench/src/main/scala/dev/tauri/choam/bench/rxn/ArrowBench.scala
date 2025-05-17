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
 * rxn1.flatMapF(rxn2)
 * ```
 *
 * In theory, by using `>>>` we avoid at least a closure allocation.
 * But does that matter in semi-realistic situations? That's the
 * question that this benchmark tries to answer.
 */
@Fork(3)
@Threads(2)
class ArrowBench {

  import ArrowBench.{ SharedSt, ThreadSt, N }

  @Benchmark
  def node1Arrow(s: SharedSt, k: ThreadSt, r: RandomState): Unit = {
    val head = s.headRefs(r.nextIntBounded(N))
    s.nodeSetOnceWithArrow(head, k.newNode).unsafePerformInternal0(null, k.mcasCtx)
  }

  @Benchmark
  def node1Monad(s: SharedSt, k: ThreadSt, r: RandomState): Unit = {
    val head = s.headRefs(r.nextIntBounded(N))
    s.nodeSetOnceWithMonad(head, k.newNode).unsafePerformInternal0(null, k.mcasCtx)
  }

  @Benchmark
  def node2Arrow(s: SharedSt, k: ThreadSt, r: RandomState): Unit = {
    val head = s.headRefs(r.nextIntBounded(N))
    val tail = s.tailRefs(r.nextIntBounded(N))
    s.nodeSetTwiceWithArrow(head, tail, k.newNode).unsafePerformInternal0(null, k.mcasCtx)
  }

  @Benchmark
  def node2Monad(s: SharedSt, k: ThreadSt, r: RandomState): Unit = {
    val head = s.headRefs(r.nextIntBounded(N))
    val tail = s.tailRefs(r.nextIntBounded(N))
    s.nodeSetTwiceWithMonad(head, tail, k.newNode).unsafePerformInternal0(null, k.mcasCtx)
  }

  @Benchmark
  def promiseArrow(s: SharedSt, k: ThreadSt, r: RandomState): Boolean = {
    val ref = s.promiseRefs(r.nextIntBounded(N))
    s.promiseReplaceAndCompleteWithArrow(ref, k.newPromise, "foo").unsafePerformInternal0(null, k.mcasCtx)
  }

  @Benchmark
  def promiseMonad(s: SharedSt, k: ThreadSt, r: RandomState): Boolean = {
    val ref = s.promiseRefs(r.nextIntBounded(N))
    s.promiseReplaceAndCompleteWithMonad(ref, k.newPromise, "foo").unsafePerformInternal0(null, k.mcasCtx)
  }
}

object ArrowBench {

  final val N = 32

  @State(Scope.Thread)
  class ThreadSt extends McasImplState {

    private[this] val preallocatedPromise: Promise[String] =
      Promise[String].unsafeRun(this.mcasImpl)

    private[this] val preallocatedNode: Node =
      ArrowBench.newNode(42, null, null).unsafeRun(this.mcasImpl)

    /**
     * We're returning a pre-allocated `Promise`, because
     * otherwise we're mostly measuring the cost of
     * allocating a new instance.
     */
    val newPromise: Axn[Promise[String]] =
      Axn.unsafe.delay { this.preallocatedPromise }

    /**
     * We're returning a pre-allocated `Node`, because
     * otherwise we're mostly measuring the cost of
     * allocating a new instance.
     */
    val newNode: Axn[Node] =
      Axn.unsafe.delay { this.preallocatedNode }
  }

  @State(Scope.Benchmark)
  class SharedSt extends McasImplStateBase {

    // Scenario #1: Promise

    val promiseRefs: Array[Ref[Promise[String]]] = Array.fill(N) {
      Ref.unsafePadded[Promise[String]](
        null : Promise[String],
        this.mcasImpl.currentContext().refIdGen,
      )
    }

    final def promiseReplaceAndCompleteWithArrow(ref: Ref[Promise[String]], newPromise: Axn[Promise[String]], str: String): Rxn[Any, Boolean] = {
      newPromise >>> ref.getAndSet.flatMapF { ov =>
        if (ov ne null) ov.complete1(str) else Rxn.pure(true)
      }
    }

    final def promiseReplaceAndCompleteWithMonad(ref: Ref[Promise[String]], newPromise: Axn[Promise[String]], str: String): Rxn[Any, Boolean] = {
      newPromise.flatMapF { p =>
        ref.getAndUpdate { _ => p }.flatMapF { ov =>
          if (ov ne null) ov.complete1(str) else Rxn.pure(true)
        }
      }
    }

    // Scenario #2: Nodes

    val headRefs: Array[Ref[Node]] = Array.fill(N) {
      Ref.unsafePadded[Node](
        null : Node,
        this.mcasImpl.currentContext().refIdGen,
      )
    }

    val tailRefs: Array[Ref[Node]] = Array.fill(N) {
      Ref.unsafePadded[Node](
        null : Node,
        this.mcasImpl.currentContext().refIdGen,
      )
    }

    final def nodeSetOnceWithArrow(head: Ref[Node], newNode: Axn[Node]): Rxn[Any, Unit] = {
      newNode >>> head.set0
    }

    final def nodeSetOnceWithMonad(head: Ref[Node], newNode: Axn[Node]): Rxn[Any, Unit] = {
      newNode.flatMapF { node =>
        head.set1(node)
      }
    }

    final def nodeSetTwiceWithArrow(head: Ref[Node], tail: Ref[Node], newNode: Axn[Node]): Rxn[Any, Unit] = {
      newNode >>> (head.set0 * tail.set0).void
    }

    final def nodeSetTwiceWithMonad(head: Ref[Node], tail: Ref[Node], newNode: Axn[Node]): Rxn[Any, Unit] = {
      newNode.flatMapF { node =>
        head.set1(node) *> tail.set1(node)
      }
    }
  }

  final class Node(val payload: Int, val prev: Ref[Node], val next: Ref[Node])

  private[ArrowBench] final def newNode(payload: Int, prev: Node, next: Node): Axn[Node] = {
    Ref[Node](prev).flatMapF { prev =>
      Ref[Node](next).map { next =>
        new Node(payload, prev, next)
      }
    }
  }
}
