/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

/**
 * MChan from the "Composable Memory Transactions" paper
 * by Harris, Marlow, Peyton Jones, and Herlihy.
 */
private sealed abstract class TMChan[A] { // TODO: figure out if we want this public (probably not)

  def write(a: A): Txn[Unit]

  def newPort: Txn[TMChan.Port[A]]
}

private object TMChan {

  final def apply[A]: Txn[TMChan[A]] = TRef[Option[(A, Node[A])]](None).flatMap { r =>
    TRef(new Node(r)).map(new TMChanImpl(_))
  }

  sealed abstract class Port[A] {
    def read: Txn[A]
  }

  private[this] final class Node[A](val ref: TRef[Option[(A, Node[A])]])

  private[this] final class TMChanImpl[A](mc: TRef[Node[A]]) extends TMChan[A] {

    final override def write(a: A): Txn[Unit] = mc.get.flatMap { c =>
      TRef[Option[(A, Node[A])]](None).flatMap { cc =>
        val node = new Node(cc)
        c.ref.set(Some((a, node))) *> mc.set(node)
      }
    }

    final override def newPort: Txn[Port[A]] = mc.get.flatMap { node =>
      TRef(node).map(new PortImpl(_))
    }
  }

  private[this] final class PortImpl[A](p: TRef[Node[A]]) extends Port[A] {
    final override def read: Txn[A] = p.get.flatMap { c =>
      c.ref.get.flatMap {
        case None =>
          Txn.retry
        case Some((v, cc)) =>
          p.set(cc).as(v)
      }
    }
  }
}
