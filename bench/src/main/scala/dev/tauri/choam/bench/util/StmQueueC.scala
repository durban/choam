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
package bench
package util

import cats.syntax.all._
import cats.effect.Concurrent

import io.github.timwspence.cats.stm.{ STMLike }

object StmQueueC {

  def make[S[F[_]] <: STMLike[F], F[_] : Concurrent, A](q: StmQueueCLike[S, F])(els: List[A]): q.stm.Txn[q.StmQueueC[A]] = {
    import q._
    import q.stm._
    for {
      end <- TVar.of[Elem[A]](End[A]())
      sentinel = Node[A](nullOf[A], end)
      head <- TVar.of[Node[A]](sentinel)
      tail <- TVar.of[Node[A]](sentinel)
      q = new StmQueueC[A](head, tail)
      _ <- els.traverse(q.enqueue(_))
    } yield q
  }
}

object StmQueueCLike {

  def apply[S[F[_]] <: STMLike[F], F[_]](s: S[F]): StmQueueCLike[S, F] { val stm: s.type {} } =
    new StmQueueCLike[S, F] { val stm: s.type = s }
}

abstract class StmQueueCLike[S[F[_]] <: STMLike[F], F[_]]  {

  val stm: S[F]

  import stm._

  sealed trait Elem[A]
  case class Node[A](data: A, next: TVar[Elem[A]]) extends Elem[A]
  case class End[A]() extends Elem[A]

  final class StmQueueC[A](
    head: TVar[Node[A]],
    tail: TVar[Node[A]]
  ) {

    def enqueue(a: A)(implicit F: Concurrent[F]): Txn[Unit] = {
      for {
        end <- TVar.of[Elem[A]](End[A]())
        node = Node(a, end)
        t <- tail.get
        tn <- t.next.get
        _ <- tn match {
          case End() =>
            t.next.set(node) >> tail.set(node)
          case Node(_, _) =>
            Txn.monadForTxn.raiseError(new IllegalStateException("lagging tail"))
        }
      } yield ()
    }

    def tryDequeue: Txn[Option[A]] = {
      for {
        h <- head.get
        hn <- h.next.get
        r <- hn match {
          case n @ Node(a, _) =>
            head.set(n.copy(data = nullOf[A])).as(a.some)
          case End() =>
            Txn.monadForTxn.pure(None)
        }
      } yield r
    }

    def toList: Txn[List[A]] = {
      def go(e: Elem[A], acc: List[A]): Txn[List[A]] = e match {
        case Node(null, next) =>
          // sentinel
          next.get.flatMap { go(_, acc) }
        case Node(a, next) =>
          next.get.flatMap { go(_, a :: acc) }
        case End() =>
          Txn.monadForTxn.pure(acc)
      }

      head.get.flatMap { go(_, Nil) }.map(_.reverse)
    }
  }
}
