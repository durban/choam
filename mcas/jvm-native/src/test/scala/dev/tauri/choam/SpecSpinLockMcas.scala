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

import internal.mcas.OsRng

import munit.Suite

trait SpecSpinLockMcas extends Suite with McasImplSpec {

  private[this] val _osRng: OsRng =
    OsRng.mkNew()

  final override val mcasImpl: internal.mcas.Mcas =
    internal.mcas.Mcas.newSpinLockMcas(_osRng, java.lang.Runtime.getRuntime().availableProcessors())

  override def afterAll(): Unit = {
    this.mcasImpl.close()
    this._osRng.close()
    super.afterAll()
  }
}
