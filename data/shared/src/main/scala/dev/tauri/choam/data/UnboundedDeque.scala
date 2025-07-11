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

import core.{ Ref, Rxn }

private[choam] sealed abstract class UnboundedDeque[A] { // TODO: consider making it public

  def addFirst(a: A): Rxn[Unit]

  def addLast(a: A): Rxn[Unit]

  def tryTakeFirst: Rxn[Option[A]]

  def tryTakeLast: Rxn[Option[A]]
}

private[choam] object UnboundedDeque {

  final def apply[A]: Rxn[UnboundedDeque[A]] = {
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

    def addFirst(a: A): Rxn[Unit] = first.get.flatMapF { fst =>
      if (fst.isSentinel) {
        newNode(a, prev = null, next = null).flatMap { node =>
          first.set(node) *> last.set(node)
        }
      } else {
        newNode(a, prev = null, next = fst).flatMap { node =>
          fst.prev.set(node) *> first.set(node)
        }
      }
    }

    def addLast(a: A): Rxn[Unit] = last.get.flatMapF { lst =>
      if (lst.isSentinel) {
        newNode(a, prev = null, next = null).flatMap { node =>
          last.set(node) *> first.set(node)
        }
      } else {
        newNode(a, prev = lst, next = null).flatMap { node =>
          lst.next.set(node) *> last.set(node)
        }
      }
    }

    def tryTakeFirst: Rxn[Option[A]] = first.get.flatMapF { fst =>
      if (fst.isSentinel) {
        Rxn.none
      } else {
        fst.next.get.flatMapF { nxt =>
          if (nxt eq null) {
            newSentinelNode[A].flatMap { node =>
              first.set(node) *> last.set(node)
            }
          } else {
            nxt.prev.set1(null) *> first.set1(nxt)
          }
        }.as(Some(fst.a)).postCommit(fst.next.set1(null)) // postCommit to help GC
      }
    }

    def tryTakeLast: Rxn[Option[A]] = last.get.flatMapF { lst =>
      if (lst.isSentinel) {
        Rxn.none
      } else {
        lst.prev.get.flatMapF { prv =>
          if (prv eq null) {
            newSentinelNode[A].flatMap { node =>
              last.set(node) *> first.set(node)
            }
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

  private[this] final def newNode[A](a: A, prev: Node[A], next: Node[A]): Rxn[Node[A]] = {
    Ref[Node[A]](prev).flatMapF { prev =>
      Ref[Node[A]](next).map { next =>
        new Node(a, prev, next)
      }
    }
  }

  private[this] val _sentinel: AnyRef =
    new AnyRef

  private[this] final def newSentinelNode[A]: Rxn[Node[A]] = {
    Ref[Node[A]](null).flatMapF { prev =>
      Ref[Node[A]](null).map { next =>
        new Node(_sentinel.asInstanceOf[A], prev, next)
      }
    }
  }
}
