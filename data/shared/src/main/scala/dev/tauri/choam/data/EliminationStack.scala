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

import java.util.concurrent.ThreadLocalRandom

import core.{ Rxn, Ref, EliminatorImpl, Eliminator }

private final class EliminationStack[A](underlying: Stack[A])
  extends EliminatorImpl[A, Unit, Any, Option[A]](underlying.push, Some(_), _ => underlying.poll, _ => ())
  // TODO: ^-- Unit could be Any(?); thus simplifying the conversion lambdas
  with Stack.UnsealedStack[A] {

  final def push(a: A): Rxn[Unit] =
    this.leftOp(a)

  final def poll: Rxn[Option[A]] =
    this.rightOp(null)

  final def peek: Rxn[Option[A]] =
    underlying.peek

  private[choam] final def size: Rxn[Int] =
    this.underlying.size
}

private object EliminationStack {

  final def apply[A]: Rxn[Stack[A]] = {
    // Note: we unconditionally use a Padded alloc.
    // strategy, because if one uses an elimination
    // stack, then contention is likely high, so
    // Padded should be also useful. In other words,
    // an unpadded elimination stack would be strange.
    TreiberStack[A](Ref.AllocationStrategy.Padded).flatMap { ul =>
      Rxn.unsafe.delay { new EliminationStack[A](ul) }
    }
  }

  sealed trait TaggedEliminationStack[A] {
    def push(a: A): Rxn[Either[Unit, Unit]]
    def tryPop: Rxn[Either[Option[A], Option[A]]]
  }

  final def tagged[A]: Rxn[TaggedEliminationStack[A]] = {
    TreiberStack[A](Ref.AllocationStrategy.Padded).flatMap { ul =>
      taggedFrom(ul.push, ul.poll)
    }
  }

  final def taggedFlaky[A]: Rxn[TaggedEliminationStack[A]] = {
    TreiberStack[A](Ref.AllocationStrategy.Padded).flatMap { ul =>
      taggedFrom(
        a => ul.push(a).flatMap { x =>
          if (ThreadLocalRandom.current().nextBoolean()) Rxn.pure(x)
          else Rxn.unsafe.retry[Unit]
        },
        ul.poll.flatMap {
          case None =>
            Rxn.none
          case s @ Some(_) =>
            if (ThreadLocalRandom.current().nextBoolean()) Rxn.pure(s)
            else Rxn.unsafe.retry[Option[A]]
        },
      )
    }
  }

  private[this] final def taggedFrom[A](
    push: A => Rxn[Unit],
    tryPop: Rxn[Option[A]],
  ): Rxn[TaggedEliminationStack[A]] = {
    Eliminator.tagged[A, Unit, Any, Option[A]](
      push,
      Some(_),
      _ => tryPop,
      _ => (),
    ).map { elim =>
      new TaggedEliminationStack[A] {

        final override def push(a: A): Rxn[Either[Unit, Unit]] =
          elim.leftOp(a)

        final override def tryPop: Rxn[Either[Option[A], Option[A]]] =
          elim.rightOp(null)
      }
    }
  }
}
