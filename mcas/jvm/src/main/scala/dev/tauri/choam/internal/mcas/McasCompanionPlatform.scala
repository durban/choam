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
package internal
package mcas

private[mcas] abstract class McasCompanionPlatform extends AbstractMcasCompanionPlatform {

  /** The single existing instance of `Emcas` */
  private[this] val _emcas =
    new emcas.Emcas

  private[mcas] final def internalEmcas: emcas.Emcas =
    _emcas

  /**
   * The default MCAS implementation of the platform
   *
   * Guaranteed to exist (and be thread-safe) on every platform.
   */
  final override def DefaultMcas: Mcas =
    this.Emcas

  final def Emcas: Mcas =
    _emcas

  final def SpinLockMcas: Mcas =
    mcas.SpinLockMcas

  /** Benchmark infra */
  private[choam] final override def unsafeLookup(fqn: String): Mcas = fqn match {
    case fqns.SpinLockMCAS =>
      mcas.SpinLockMcas
    case fqns.Emcas =>
      this.Emcas
    case x =>
      super.unsafeLookup(x)
  }

  /** Benchmark infra */
  private[choam] object fqns {
    final val SpinLockMCAS =
      "dev.tauri.choam.mcas.SpinLockMcas"
    final val Emcas =
      "dev.tauri.choam.mcas.emcas.Emcas"
  }
}
