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

sealed abstract class AllocationStrategy {

  def padded: Boolean
  def withPadded(padded: Boolean): AllocationStrategy

  def sparse: Boolean
  def withSparse(sparse: Boolean): AllocationStrategy

  def flat: Boolean
  def withFlat(flat: Boolean): AllocationStrategy

  private[choam] def stm: Boolean
  private[choam] def withStm(stm: Boolean): AllocationStrategy
}

object AllocationStrategy {

  val Default: AllocationStrategy = apply(
    padded = false,
    sparse = false,
    flat = true,
    stm = false,
  )

  val Padded: AllocationStrategy =
    Default.withPadded(true)

  val Unpadded: AllocationStrategy =
    Default.withPadded(false)

  private[choam] val SparseFlat: AllocationStrategy = apply(
    padded = false,
    sparse = true,
    flat = true,
    stm = false,
  )

  final def apply(
    padded: Boolean,
    sparse: Boolean,
    flat: Boolean,
  ): AllocationStrategy = apply(
    padded = padded,
    sparse = sparse,
    flat = flat,
    stm = false,
  )

  private[choam] final def apply(
    padded: Boolean,
    sparse: Boolean,
    flat: Boolean,
    stm: Boolean,
  ): AllocationStrategy = new AllocStrImpl(
    padded = padded,
    sparse = sparse,
    flat = flat,
    stm = stm,
  )

  private final class AllocStrImpl(
    final override val padded: Boolean,
    final override val sparse: Boolean,
    final override val flat: Boolean,
    private[choam] final override val stm: Boolean,
  ) extends AllocationStrategy {

    require(!(padded && flat), "padding is currently not supported for flat = true")

    final override def withPadded(padded: Boolean): AllocationStrategy =
      this.copy(padded = padded)

    final override def withSparse(sparse: Boolean): AllocationStrategy =
      this.copy(sparse = sparse)

    final override def withFlat(flat: Boolean): AllocationStrategy =
      this.copy(flat = flat)

    private[choam] final override def withStm(stm: Boolean): AllocationStrategy =
      this.copy(stm = stm)

    private final def copy(
      padded: Boolean = this.padded,
      sparse: Boolean = this.sparse,
      flat: Boolean = this.flat,
      stm: Boolean = this.stm,
    ): AllocationStrategy = {
      if (
        (padded == this.padded) &&
        (sparse == this.sparse) &&
        (flat == this.flat) &&
        (stm == this.stm)
      ) {
        this
      } else {
        new AllocStrImpl(
          padded = padded,
          sparse = sparse,
          flat = flat,
          stm = stm,
        )
      }
    }
  }
}
