/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

private[mcas] abstract class McasCompanionMultithreaded extends AbstractMcasCompanionPlatform {

  private[choam] final def newEmcas(osRng: OsRng, numCpu: Int): Mcas =
    new emcas.Emcas(osRng, numCpu)

  private[choam] final def newSpinLockMcas(osRng: OsRng, numCpu: Int): Mcas =
    new SpinLockMcas(osRng, numCpu)

  protected[this] final override def newMcasFromSystemProperty(sysProp: String, osRng: OsRng, numCpu: Int): Mcas = {
    sysProp match {
      case null | "" | "Emcas" =>
        this.newEmcas(osRng, numCpu)
      case "SpinLockMcas" =>
        this.newSpinLockMcas(osRng, numCpu)
      case "ThreadConfinedMcas" =>
        _assert(numCpu == 1)
        this.newThreadConfinedMcas(osRng)
      case x =>
        throw new IllegalArgumentException("invalid MCAS name: " + x)
    }
  }
}
