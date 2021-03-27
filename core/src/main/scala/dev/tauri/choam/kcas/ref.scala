/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

import java.util.concurrent.ThreadLocalRandom

/** k-CAS-able atomic reference */
trait Ref[A] extends mcas.MemoryLocation[A] {

  final def upd[B, C](f: (A, B) => (A, C)): React[B, C] =
    React.upd(this)(f)

  final def updWith[B, C](f: (A, B) => React[Any, (A, C)]): React[B, C] =
    React.updWith(this)(f)

  final def modify(f: A => A): React[Any, A] =
    upd[Any, A] { (a, _) => (f(a), a) }

  final def modify2[B](f: A => (A, B)): React[Unit, B] =
    upd[Unit, B] { (a, _) => f(a) }

  final def modifyWith(f: A => React[Any, A]): React[Unit, A] =
    updWith[Unit, A] { (oa, _) => f(oa).map(na => (na, oa)) }

  final def unsafeInvisibleRead: React[Any, A] =
    React.unsafe.invisibleRead(this)

  final def getter: React[Any, A] =
    upd[Any, A] { (a, _) => (a, a) }

  final def unsafeCas(ov: A, nv: A): React[Any, Unit] =
    React.unsafe.cas(this, ov, nv)

  /** For testing */
  private[choam] final def debugRead(): A = {
    this.unsafeGetVolatile() match {
      case null =>
        kcas.NaiveKCAS.read(this, kcas.NaiveKCAS.currentContext())
      case _: kcas.WordDescriptor[_] =>
        kcas.EMCAS.read(this, kcas.EMCAS.currentContext())
      case a =>
        a
    }
  }

  private[kcas] def dummy(v: Long): Long
}

object Ref {

  // TODO: `Ref.empty[A]`, for creating an uninitialized ref (it
  // TODO: should use one barrier less than `Ref.mk[A](nullOf[A])`).

  implicit final class BooleanRefOps(private val self: Ref[Boolean]) extends AnyVal {

    def guard[A, B](guarded: React[A, B]): React[A, Option[B]] =
      guardImpl(guarded, negate = false)

    def guardNot[A, B](guarded: React[A, B]): React[A, Option[B]] =
      guardImpl(guarded, negate = true)

    private def guardImpl[A, B](guarded: React[A, B], negate: Boolean): React[A, Option[B]] = {
      (self.unsafeInvisibleRead × React.identity[A]).flatMap {
        case (guard, _) =>
          if (guard ^ negate) {
            (self.unsafeCas(guard, guard) × guarded.rmap(Some(_))).rmap(_._2)
          } else {
            self.unsafeCas(guard, guard).lmap[(Unit, A)](_ => ()).rmap(_ => None)
          }
      }.lmap[A](a => ((), a))
    }
  }

  def apply[A](initial: A): React[Any, Ref[A]] =
    React.delay[Any, Ref[A]](_ => Ref.unsafe(initial))

  def unsafe[A](initial: A): Ref[A] = {
    val tlr = ThreadLocalRandom.current()
    mkWithId(initial)(tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextLong())
  }

  /** Only for testing */
  private[kcas] def mkWithId[A](a: A)(i0: Long, i1: Long, i2: Long, i3: Long): Ref[A] = {
    new ref.Ref1(a, i0, i1, i2, i3)
  }

  /**
   * Only for testing
   *
   * TODO: provide unpadded groups of refs
   * (e.g., Ref2, Ref3) which still have
   * padding at the end.
   */
  private[kcas] def mkUnpadded[A](a: A): Ref[A] = {
    val tlr = ThreadLocalRandom.current()
    new ref.UnpaddedRef1(a, tlr.nextLong(), tlr.nextLong(), tlr.nextLong(), tlr.nextLong())
  }

  // TODO: public API(?)
  private[choam] def ref2[A, B](a: A, b: B): ref.Ref2[A, B] = {
    val tlr = ThreadLocalRandom.current()
    new ref.Ref2Impl[A, B](
      a,
      b,
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong()
    )
  }
}
