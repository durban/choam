/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
package mcas

private[mcas] abstract class MCASPlatform extends AbstractMCASPlatform {

  /**
   * The default MCAS implementation of the platform
   *
   * Guaranteed to exist (and be thread-safe) on every platform.
   */
  final override def DefaultMCAS: MCAS =
    this.EMCAS

  final def EMCAS: MCAS =
    mcas.EMCAS

  final def SpinLockMCAS: MCAS =
    mcas.SpinLockMCAS

  /** For testing */
  private[choam] final override def debugRead[A](loc: MemoryLocation[A]): A = {
    loc.unsafeGetVolatile() match {
      case null =>
        SpinLockMCAS.currentContext().read(loc)
      case _: WordDescriptor[_] =>
        EMCAS.currentContext().read(loc)
      case a =>
        a
    }
  }

  /** Benchmark infra */
  private[choam] final override def unsafeLookup(fqn: String): MCAS = fqn match {
    case fqns.SpinLockMCAS =>
      mcas.SpinLockMCAS
    case fqns.EMCAS =>
      mcas.EMCAS
    case x =>
      super.unsafeLookup(x)
  }

  /** Benchmark infra */
  private[choam] object fqns {
    final val SpinLockMCAS =
      "dev.tauri.choam.mcas.SpinLockMCAS"
    final val EMCAS =
      "dev.tauri.choam.mcas.EMCAS"
  }
}
