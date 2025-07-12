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
package data

import core.{ Rxn, Ref, Reactive }
import internal.mcas.RefIdGen
import GcHostileMsQueue._

/**
 * Unoptimized Michael-Scott queue (for benchmarks)
 *
 * It is GC-hostile, because it allocates a lot of
 * objects, including `Ref`s, which are only used once
 * (see `GcBench`), and also doesn't clear the next
 * link of dequed nodes (which can become cross-generational,
 * which is harder for the GC to deal with).
 *
 * It also lacks other optimizations present in
 * `MsQueue` (see there).
 */
private final class GcHostileMsQueue[A] private[this] (sentinel: Node[A], initRig: RefIdGen)
  extends Queue.UnsealedQueue[A] {

  private[this] val head: Ref[Node[A]] = Ref.unsafePadded(sentinel, initRig)
  private[this] val tail: Ref[Node[A]] = Ref.unsafePadded(sentinel, initRig)

  private def this(initRig: RefIdGen) =
    this(Node(nullOf[A], Ref.unsafePadded(End[A](), initRig)), initRig)

  final override val poll: Rxn[Option[A]] = {
    head.modifyWith { node =>
      node.next.get.flatMap { next =>
        next match {
          case n @ Node(a, _) =>
            Rxn.pure((n.copy(data = nullOf[A]), Some(a)))
          case End() =>
            Rxn.pure((node, None))
        }
      }
    }
  }

  final override def enqueue(a: A): Rxn[Unit] = {
    Ref.padded[Elem[A]](End()).flatMap { newRef =>
      findAndEnqueue(Node(a, newRef))
    }
  }

  final override def offer(a: A): Rxn[Boolean] =
    this.enqueue(a).as(true)

  private[this] def findAndEnqueue(node: Node[A]): Rxn[Unit] = {
    def go(n: Node[A]): Rxn[Unit] = {
      n.next.get.flatMap {
        case End() =>
          // found true tail; will update, and adjust the tail ref:
          n.next.set(node) *> tail.set(node)
        case nv @ Node(_, _) =>
          // not the true tail; try to catch up, and continue:
          go(n = nv)
      }
    }
    tail.get.flatMap(go)
  }
}

private object GcHostileMsQueue {

  private sealed trait Elem[A]
  private final case class Node[A](data: A, next: Ref[Elem[A]]) extends Elem[A]
  private final case class End[A]() extends Elem[A]

  def apply[A]: Rxn[GcHostileMsQueue[A]] =
    Rxn.unsafe.delayContext { ctx => new GcHostileMsQueue[A](ctx.refIdGen) }

  def fromList[F[_], A](as: List[A])(implicit F: Reactive[F]): F[GcHostileMsQueue[A]] = {
    Queue.fromList[F, GcHostileMsQueue, A](this.apply[A])(as)
  }
}
