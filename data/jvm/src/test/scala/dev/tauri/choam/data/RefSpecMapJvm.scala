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
package data

import cats.kernel.Hash
import cats.effect.IO

final class RefSpec_Map_Ttrie_ThreadConfinedMCAS_IO
  extends BaseSpecIO
  with SpecThreadConfinedMCAS
  with RefSpec_Map_Ttrie[IO]

final class RefSpec_Map_Ttrie_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with RefSpec_Map_Ttrie[IO]

final class RefSpec_Map_Ttrie_SpinLockMCAS_IO
  extends BaseSpecIO
  with SpecSpinLockMCAS
  with RefSpec_Map_Ttrie[IO]

final class RefSpec_Map_Simple_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with RefSpec_Map_Simple[IO]

final class RefSpec_Map_Simple_SpinLockMCAS_IO
  extends BaseSpecIO
  with SpecSpinLockMCAS
  with RefSpec_Map_Simple[IO]

trait RefSpec_Map_Ttrie[F[_]] extends RefSpecMap[F] { this: KCASImplSpec =>

  final override type MapType[K, V] = Map[K, V]

  final override def newMap[K: Hash, V]: F[MapType[K, V]] =
    Map.ttrie[K, V].run[F]
}
