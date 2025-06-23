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

import core.{ Rxn, Axn, Ref, EliminatorImpl, Eliminator }

private final class EliminationStack[A](underlying: Stack[A])
  extends EliminatorImpl[A, Unit, Any, Option[A]](underlying.push, Some(_), underlying.tryPop, _ => ())
  with Stack.UnsealedStack[A] {

  final def push: Rxn[A, Unit] =
    this.leftOp

  final def tryPop: Axn[Option[A]] =
    this.rightOp

  final def size: Axn[Int] =
    this.underlying.size
}

private object EliminationStack {

  final def apply[A](str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default): Axn[Stack[A]] = {
    Stack.treiberStack[A](str).flatMapF { ul =>
      Axn.unsafe.delay { new EliminationStack[A](ul) }
    }
  }

  sealed trait TaggedEliminationStack[A] {
    def push: Rxn[A, Either[Unit, Unit]]
    def tryPop: Axn[Either[Option[A], Option[A]]]
  }

  final def tagged[A](str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default): Axn[TaggedEliminationStack[A]] = {
    Stack.treiberStack[A](str).flatMapF { ul =>
      taggedFrom(ul.push, ul.tryPop)
    }
  }

  final def taggedFlaky[A](str: Ref.AllocationStrategy = Ref.AllocationStrategy.Default): Axn[TaggedEliminationStack[A]] = {
    Stack.treiberStack[A](str).flatMapF { ul =>
      taggedFrom(
        ul.push.flatMapF { x =>
          if (ThreadLocalRandom.current().nextBoolean()) Axn.pure(x)
          else Rxn.unsafe.retry[Unit]
        },
        ul.tryPop.flatMapF {
          case None =>
            Axn.none
          case s @ Some(_) =>
            if (ThreadLocalRandom.current().nextBoolean()) Axn.pure(s)
            else Rxn.unsafe.retry[Option[A]]
        },
      )
    }
  }

  private[this] final def taggedFrom[A](
    push: Rxn[A, Unit],
    tryPop: Axn[Option[A]],
  ): Axn[TaggedEliminationStack[A]] = {
    Eliminator.tagged[A, Unit, Any, Option[A]](
      push,
      Some(_),
      tryPop,
      _ => (),
    ).map { elim =>
      new TaggedEliminationStack[A] {

        final override def push: Rxn[A, Either[Unit, Unit]] =
          elim.leftOp

        final override def tryPop: Axn[Either[Option[A], Option[A]]] =
          elim.rightOp
      }
    }
  }
}
