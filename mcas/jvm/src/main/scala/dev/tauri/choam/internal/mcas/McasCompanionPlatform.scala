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

  private[this] val _deprecatedEmcas: Mcas = // TODO:0.5: remove
    this.newEmcas(OsRng.globalLazyInit())

  private[choam] final override def newDefaultMcas(osRng: OsRng): Mcas =
    this.newEmcas(osRng)

  @deprecated("Emcas singleton will be removed", since = "0.4.11") // TODO:0.5: remove
  final def Emcas: Mcas =
    _deprecatedEmcas

  private[choam] final def newEmcas(osRng: OsRng): Mcas =
    new emcas.Emcas(osRng)

  final def SpinLockMcas: Mcas =
    mcas.SpinLockMcas

  /** Benchmark infra */
  @nowarn("cat=deprecation")
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
