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

import internal.mcas.{ Mcas, RefIdGen }

trait McasImplSpec {

  protected def mcasImpl: Mcas

  protected final def rigInstance: RefIdGen =
    this.mcasImpl.currentContext().refIdGen

  protected def isEmcas: Boolean =
    false // EMCAS must override
}

trait SpecDefaultMcas extends munit.Suite with SpecDefaultMcasPlatform {

  private[this] val _mcasImpl: Mcas =
    Mcas.newDefaultMcas(BaseSpec.osRngForTesting)

  override def afterAll(): Unit = {
    this._mcasImpl.close()
    super.afterAll()
  }

  final override def mcasImpl: Mcas =
    this._mcasImpl
}

trait SpecThreadConfinedMcas extends McasImplSpec {
  final override def mcasImpl: Mcas =
    Mcas.ThreadConfinedMCAS
}

trait SpecNullMcas extends McasImplSpec {
  final override def mcasImpl: Mcas =
    Mcas.NullMcas
}

trait SpecNoMcas extends McasImplSpec {
  final override def mcasImpl: Nothing =
    sys.error("No MCAS here")
}
