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

private[mcas] abstract class McasCompanionPlatform extends AbstractMcasCompanionPlatform {

  /**
   * The default MCAS implementation of the platform
   *
   * Guaranteed to exist (and be thread-safe) on every platform.
   */
  final override def DefaultMcas: Mcas =
    this.Emcas

  final def Emcas: Mcas =
    mcas.Emcas

  final def SpinLockMcas: Mcas =
    mcas.SpinLockMcas

  /** For testing */
  private[choam] final override def debugRead[A](loc: MemoryLocation[A]): A = {
    loc.unsafeGetVolatile() match {
      case null =>
        SpinLockMcas.currentContext().readDirect(loc)
      case _: WordDescriptor[_] =>
        Emcas.currentContext().readDirect(loc)
      case a =>
        a
    }
  }

  /** Benchmark infra */
  private[choam] final override def unsafeLookup(fqn: String): Mcas = fqn match {
    case fqns.SpinLockMCAS =>
      mcas.SpinLockMcas
    case fqns.Emcas =>
      mcas.Emcas
    case x =>
      super.unsafeLookup(x)
  }

  /** Benchmark infra */
  private[choam] object fqns {
    final val SpinLockMCAS =
      "dev.tauri.choam.mcas.SpinLockMcas"
    final val Emcas =
      "dev.tauri.choam.mcas.Emcas"
  }
}
