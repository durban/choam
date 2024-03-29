/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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

import internal.mcas.Mcas

trait McasImplSpec {
  protected def mcasImpl: Mcas
  protected def isEmcas: Boolean =
    false
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
