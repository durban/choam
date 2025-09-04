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
package stm

import core.Ref
import dev.tauri.choam.stm.{ Txn, TRef }

sealed abstract class TQueue[A] { // TODO: move it to -data(?)
  def take: Txn[A]
  def put(a: A): Txn[Unit]
}

object TQueue {

  final def unboundedWrapped[A]: Txn[TQueue[A]] = {
    WQueue.unbounded[A]
  }

  final def unboundedDirect[A]: Txn[TQueue[A]] = { // TODO: a MS-queue might be more efficient
    TRef(List.empty[A]).flatMap { reads =>
      TRef(List.empty[A]).map { writes =>
        new TQueue[A] {

          final override def put(a: A): Txn[Unit] = {
            writes.update(a :: _)
          }

          final override def take: Txn[A] = for {
            r <- reads.get
            a <- r match {
              case head :: tail =>
                reads.set(tail).as(head)
              case Nil =>
                writes.get.flatMap { w =>
                  w match {
                    case w @ (_ :: _) =>
                      val rw = w.reverse
                      writes.set(Nil) *> reads.set(rw.tail).as(rw.head)
                    case Nil =>
                      Txn.retry
                  }
                }
            }
          } yield a
        }
      }
    }
  }

  final object WQueue {

    final def unbounded[A]: Txn[WQueue[A]] = {
      Queue.unbounded[A](Ref.AllocationStrategy.Default.withStm(true)).impl.map { q =>
        new WQueue[A](q)
      }
    }

    final def bounded[A](bound: Int): Txn[WQueue[A]] = {
      Queue.bounded[A](bound, Ref.AllocationStrategy.Default.withStm(true)).impl.map { q =>
        new WQueue[A](q)
      }
    }
  }

  final class WQueue[A](underlying: Queue.SourceSink[A]) extends TQueue[A] {

    final override def put(a: A): Txn[Unit] = {
      underlying.offer(a).impl.flatMap {
        case true => Txn.unit
        case false => Txn.retry
      }
    }

    final override def take: Txn[A] = {
      underlying.poll.impl.flatMap {
        case Some(a) => Txn.pure(a)
        case None => Txn.retry
      }
    }
  }
}
