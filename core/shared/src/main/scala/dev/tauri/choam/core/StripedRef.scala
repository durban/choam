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
package core

private[choam] sealed abstract class StripedRef[A] {

  def current: RefLike[A]

  def fold[B](z: B)(f: (B, RefLike[A]) => B): B

  // TODO: `fold` should be able to short-circuit
}

private[choam] object StripedRef extends StripedRefCompanionPlatform {

  final def apply[A](initial: A): Axn[StripedRef[A]] = Axn.unsafe.suspendContext { ctx =>
    val size = ctx.stripes
    Ref.array(size, initial, this.strategy).map { arr =>
      new StripedRefImpl(arr)
    }
  }

  private[this] final class StripedRefImpl[A](stripes: Ref.Array[A])
    extends StripedRef[A]
    with RefLike.UnsealedRefLike[A] {

    final override def current: RefLike[A] =
      this

    final override def fold[B](z: B)(f: (B, RefLike[A]) => B): B = {
      val len = stripes.length
      @tailrec
      def go(idx: Int, acc: B): B = {
        if (idx >= len) {
          acc
        } else {
          val ref = stripes.unsafeGet(idx)
          go(idx + 1, f(acc, ref))
        }
      }

      go(0, z)
    }

    final override def get: Axn[A] = Rxn.unsafe.suspendContext { (_: Any, ctx) =>
      val ref = stripes.unsafeGet(ctx.stripeId)
      ref.get
    }

    final override def set1(a: A): Axn[Unit] = Rxn.unsafe.suspendContext { (_: Any, ctx) =>
      val ref = stripes.unsafeGet(ctx.stripeId)
      ref.set1(a)
    }

    final override def update1(f: A => A): Axn[Unit] = Rxn.unsafe.suspendContext { (_: Any, ctx) =>
      val ref = stripes.unsafeGet(ctx.stripeId)
      ref.update1(f)
    }

    final override def updWith[B, C](f: (A, B) => Axn[(A, C)]): Rxn[C] = Rxn.unsafe.suspendContext { (_: Any, ctx) =>
      val ref = stripes.unsafeGet(ctx.stripeId)
      ref.updWith(f)
    }

    final override def upd[B, C](f: (A, B) => (A, C)): Rxn[C] = Rxn.unsafe.suspendContext { (_: Any, ctx) =>
      val ref = stripes.unsafeGet(ctx.stripeId)
      ref.upd(f)
    }
  }
}
