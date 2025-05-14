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

private[choam] sealed abstract class UnboundedDeque[A] { // TODO: consider making it public

  def addFirst(a: A): Axn[Unit]

  def addLast(a: A): Axn[Unit]

  def tryTakeFirst: Axn[Option[A]]

  def tryTakeLast: Axn[Option[A]]
}

private[choam] object UnboundedDeque {

  final def apply[A]: Axn[UnboundedDeque[A]] = {
    newSentinelNode[A].flatMapF { node =>
      Ref(node).flatMapF { first =>
        Ref(node).map { last =>
          new UnboundedDequeImpl(first, last)
        }
      }
    }
  }

  private[this] final class UnboundedDequeImpl[A](
    first: Ref[Node[A]],
    last: Ref[Node[A]],
  ) extends UnboundedDeque[A] {

    def addFirst(a: A): Axn[Unit] = first.get.flatMapF { fst =>
      if (fst.isSentinel) {
        newNode(a, prev = null, next = null) >>> (first.set0 * last.set0).void
      } else {
        newNode(a, prev = null, next = fst) >>> (fst.prev.set0 * first.set0).void
      }
    }

    def addLast(a: A): Axn[Unit] = last.get.flatMapF { lst =>
      if (lst.isSentinel) {
        newNode(a, prev = null, next = null) >>> (last.set0 * first.set0).void
      } else {
        newNode(a, prev = lst, next = null) >>> (lst.next.set0 * last.set0).void
      }
    }

    def tryTakeFirst: Axn[Option[A]] = first.get.flatMapF { fst =>
      if (fst.isSentinel) {
        Axn.none
      } else {
        fst.next.get.flatMapF { nxt =>
          if (nxt eq null) {
            newSentinelNode >>> (first.set0 * last.set0)
          } else {
            nxt.prev.set1(null) *> first.set1(nxt)
          }
        }.as(Some(fst.a)).postCommit(fst.next.set1(null)) // postCommit to help GC
      }
    }

    def tryTakeLast: Axn[Option[A]] = last.get.flatMapF { lst =>
      if (lst.isSentinel) {
        Axn.none
      } else {
        lst.prev.get.flatMapF { prv =>
          if (prv eq null) {
            newSentinelNode >>> (last.set0 * first.set0)
          } else {
            prv.next.set1(null) *> last.set1(prv)
          }
        }.as(Some(lst.a)).postCommit(lst.prev.set1(null)) // postCommit to help GC
      }
    }
  }

  private[this] final class Node[A](val a: A, val prev: Ref[Node[A]], val next: Ref[Node[A]]) {
    final def isSentinel: Boolean = equ(this.a, _sentinel)
  }

  private[this] final def newNode[A](a: A, prev: Node[A], next: Node[A]): Axn[Node[A]] = {
    Ref[Node[A]](prev).flatMapF { prev =>
      Ref[Node[A]](next).map { next =>
        new Node(a, prev, next)
      }
    }
  }

  private[this] val _sentinel: AnyRef =
    new AnyRef

  private[this] final def newSentinelNode[A]: Axn[Node[A]] = {
    Ref[Node[A]](null).flatMapF { prev =>
      Ref[Node[A]](null).map { next =>
        new Node(_sentinel.asInstanceOf[A], prev, next)
      }
    }
  }
}
