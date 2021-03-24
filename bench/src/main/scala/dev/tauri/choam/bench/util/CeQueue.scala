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
package bench
package util

import cats.syntax.all._
import cats.effect.kernel.{ Concurrent, Ref }

import CeQueue._

object CeQueue {

  sealed trait Elem[F[_], A]
  case class Node[F[_], A](data: A, next: Ref[F, Elem[F, A]]) extends Elem[F, A]
  case class End[F[_], A]() extends Elem[F, A]

  def apply[F[_], A](implicit F: Concurrent[F]): F[CeQueue[F, A]] = for {
    end <- F.ref[Elem[F, A]](End[F, A]())
    sentinel = Node(nullOf[A], end)
    h <- F.ref[Node[F, A]](sentinel)
    t <- F.ref[Node[F, A]](sentinel)
  } yield new CeQueue[F, A](h, t)

  def fromList[F[_], A](lst: List[A])(implicit F: Concurrent[F]): F[CeQueue[F, A]] = {
    apply[F, A].flatMap { q =>
      lst.traverse(q.enqueue).as(q)
    }
  }
}

final class CeQueue[F[_], A](
  head: Ref[F, Node[F, A]],
  tail: Ref[F, Node[F, A]]
) {

  def enqueue(a: A)(implicit F: Concurrent[F]): F[Unit] = {
    Ref.of[F, Elem[F, A]](End()).flatMap { ref =>
      findAndEnqueue(Node(a, ref))
    }
  }

  private def findAndEnqueue(node: Node[F, A])(implicit F: Concurrent[F]): F[Unit] = {
    tail.access.flatMap { case (n, setTail) =>
      n.next.access.flatMap {
        case (End(), setNNext) =>
          // found true tail:
          setNNext(node).flatMap {
            case true =>
              // try to adjust tail ref:
              setTail(node).void
            case false =>
              // retry:
              findAndEnqueue(node)
          }
        case (nv @ Node(_, _), _) =>
          // not the true tail; try to catch up, and retry:
          setTail(nv) >> findAndEnqueue(node)
      }
    }
  }

  def tryDequeue(implicit F: Concurrent[F]): F[Option[A]] = {
    for {
      ns <- head.access
      (node, setHead) = ns
      next <- node.next.get
      sr <- next match {
        case n @ Node(a, _) =>
          setHead(n.copy(data = nullOf[A])).map { success => (success, Some(a)) }
        case End() =>
          F.pure((true, None))
      }
      (success, res) = sr
      r <- if (success) {
        F.pure(res)
      } else {
        tryDequeue // retry
      }
    } yield r
  }

  def toList(implicit F: Concurrent[F]): F[List[A]] = {
    def go(e: Elem[F, A], acc: List[A]): F[List[A]] = e match {
      case Node(null, next) =>
        // sentinel
        next.get.flatMap { go(_, acc) }
      case Node(a, next) =>
        next.get.flatMap { go(_, a :: acc) }
      case End() =>
        F.pure(acc)
    }

    head.get.flatMap { go(_, Nil) }.map(_.reverse)
  }
}
