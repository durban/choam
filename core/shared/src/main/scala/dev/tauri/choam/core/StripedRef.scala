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

import internal.mcas.Mcas

private[choam] sealed abstract class StripedRef[A] {

  def current: RefLike[A]

  def fold[B](z: B)(f: (B, RefLike[A]) => B): B

  // TODO: `fold` should be able to short-circuit
  // TODO: `fold` should work without materializing `Ref`s
}

private[choam] object StripedRef {

  final def apply[A](initial: A): Rxn[StripedRef[A]] = Rxn.unsafe.delayContext { ctx =>
    unsafe(initial, Ref.AllocationStrategy.Padded, ctx)
  }

  private[choam] final def unsafe[A](initial: A, str: Ref.AllocationStrategy, ctx: Mcas.ThreadContext): StripedRef[A] = {
    val arr = Ref.unsafeArray(ctx.stripes, initial, strFromRefStr(str), ctx.refIdGen)
    new StripedRefImpl(arr)
  }

  private[this] final def strFromRefStr(refStr: Ref.AllocationStrategy): Ref.Array.AllocationStrategy = {
    // TODO: use flat = true when it supports padding
    Ref.Array.AllocationStrategy(sparse = false, flat = false, padded = refStr.padded)
  }

  private[this] final class StripedRefImpl[A](stripes: Ref.Array[A])
    extends StripedRef[A]
    with RefLike.UnsealedRefLike[A]
    with RefLikeDefaults[A] {

    final override def current: RefLike[A] =
      this

    final override def fold[B](z: B)(f: (B, RefLike[A]) => B): B = {
      stripes.refs.foldLeft(z)(f)
    }

    final override def get: Rxn[A] = Rxn.unsafe.suspendContext { ctx =>
      stripes.unsafeGet(ctx.stripeId)
    }

    final override def modify[B](f: A => (A, B)): Rxn[B] = Rxn.unsafe.suspendContext { ctx =>
      val ref: Ref[A] = stripes.unsafeApply(ctx.stripeId) // TODO: avoid unsafeApply
      ref.modify(f)
    }

    final override def set(a: A): Rxn[Unit] = Rxn.unsafe.suspendContext { ctx =>
      stripes.unsafeSet(ctx.stripeId, a)
    }

    final override def update(f: A => A): Rxn[Unit] = Rxn.unsafe.suspendContext { ctx =>
      stripes.unsafeUpdate(ctx.stripeId, f)
    }
  }
}
