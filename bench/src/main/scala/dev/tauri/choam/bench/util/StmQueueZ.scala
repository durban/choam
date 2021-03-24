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

import zio.IO
import zio.stm.{ STM, ZSTM, TRef }

import StmQueueZ._

object StmQueueZ {

  sealed trait Elem[A]
  case class Node[A](data: A, next: TRef[Elem[A]]) extends Elem[A]
  case class End[A]() extends Elem[A]

  def apply[A](els: List[A]): IO[Nothing, StmQueueZ[A]] = for {
    end <- TRef.makeCommit[Elem[A]](End[A]())
    sentinel = Node[A](nullOf[A], end)
    head <- TRef.makeCommit[Node[A]](sentinel)
    tail <- TRef.makeCommit[Node[A]](sentinel)
    q = new StmQueueZ[A](head, tail)
    _ <- els.foldLeft[IO[Nothing, Unit]](IO.unit) { (t, a) =>
      t.flatMap { _ => STM.atomically(q.enqueue(a)) }
    }
  } yield q
}

final class StmQueueZ[A](
  head: TRef[Node[A]],
  tail: TRef[Node[A]]
) {

  def enqueue(a: A): STM[Nothing, Unit] = {
    for {
      end <- TRef.make[Elem[A]](End[A]())
      node = Node(a, end)
      t <- tail.get
      tn <- t.next.get
      _ <- tn match {
        case End() =>
          t.next.set(node).flatMap { _ => tail.set(node) }
        case tn @ Node(_, _) =>
          tail.set(tn) *> ZSTM.retry // lagging tail
      }
    } yield ()
  }

  def tryDequeue: STM[Nothing, Option[A]] = {
    for {
      h <- head.get
      hn <- h.next.get
      r <- hn match {
        case n @ Node(a, _) =>
          head.set(n.copy(data = nullOf[A])).as(Some(a))
        case End() =>
          ZSTM.succeed(None)
      }
    } yield r
  }

  def toList: STM[Nothing, List[A]] = {
    def go(e: Elem[A], acc: List[A]): STM[Nothing, List[A]] = e match {
      case Node(null, next) =>
        // sentinel
        next.get.flatMap { go(_, acc) }
      case Node(a, next) =>
        next.get.flatMap { go(_, a :: acc) }
      case End() =>
        ZSTM.succeed(acc)
    }

    head.get.flatMap { go(_, Nil) }.map(_.reverse)
  }
}
