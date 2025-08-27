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
package stm
package data

sealed abstract class TQueue[A] { // TODO: move it to -data(?)
  def take: Txn[A]
  def put(a: A): Txn[Unit]
}

object TQueue {

  final def unbounded[A]: Txn[TQueue[A]] = { // TODO: a MS-queue might be more efficient
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
}
