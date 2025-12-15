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

import core.Ref

sealed trait TArray[A] {

  def length: Int

  def unsafeGet(idx: Int): Txn[A]
  def unsafeSet(idx: Int, nv: A): Txn[Unit]
  def unsafeUpdate(idx: Int, f: A => A): Txn[Unit]

  def get(idx: Int): Txn[Option[A]]
  def set(idx: Int, nv: A): Txn[Boolean]
  def update(idx: Int, f: A => A): Txn[Boolean]

  // TODO: def refs: Vector[TRef[A]]
}

object TArray {

  final def apply[A](
    length: Int,
    initial: A,
    strategy: Ref.Array.AllocationStrategy = Ref.Array.AllocationStrategy.Default,
  ): Txn[TArray[A]] = {
    // TODO: `flat` strategy is ignored (for now); implement it (because it probably needs less memory)
    val stmStr = strategy.withStm(true)
    Ref.arrayImpl(size = length, initial = initial, strategy = stmStr).flatMap { rxnArr =>
      Txn.unsafe.delay {
        new TArray[A] {

          def length: Int =
            rxnArr.length

          // TODO: avoid these casts:

          private[this] final def unsafeGetTRef(idx: Int): TRef[A] = {
            rxnArr.unsafeGet(idx) match {
              case tref: TRef[_] => tref.asInstanceOf[TRef[A]]
              case x => impossible(s"found ${x} instead of TRef")
            }
          }

          def unsafeGet(idx: Int): Txn[A] =
            unsafeGetTRef(idx).get

          def unsafeSet(idx: Int, nv: A): Txn[Unit] =
            unsafeGetTRef(idx).set(nv)

          def unsafeUpdate(idx: Int, f: A => A): Txn[Unit] =
            unsafeGetTRef(idx).update(f)

          def get(idx: Int): Txn[Option[A]] = {
            rxnArr(idx) match {
              case Some(ref: TRef[_]) => ref.asInstanceOf[TRef[A]].get.map(Some(_))
              case Some(x) => impossible(s"found ${x} instead of TRef")
              case None => Txn.none[A]
            }
          }

          def set(idx: Int, nv: A): Txn[Boolean] = {
            rxnArr(idx) match {
              case Some(ref: TRef[_]) => ref.asInstanceOf[TRef[A]].set(nv).as(true)
              case Some(x) => impossible(s"found ${x} instead of TRef")
              case None => Txn._false
            }
          }

          def update(idx: Int, f: A => A): Txn[Boolean] = {
            rxnArr(idx) match {
              case Some(ref: TRef[_]) => ref.asInstanceOf[TRef[A]].update(f).as(true)
              case Some(x) => impossible(s"found ${x} instead of TRef")
              case None => Txn._false
            }
          }
        }
      }
    }
  }
}
