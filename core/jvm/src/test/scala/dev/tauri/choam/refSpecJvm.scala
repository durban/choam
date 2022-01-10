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

import cats.effect.IO
import dev.tauri.choam.mcas.MemoryLocation

final class RefSpecJvm_SpinLockMCAS_IO
  extends BaseSpecIO
  with SpecSpinLockMCAS
  with RefSpecJvm[IO]

final class RefSpecJvm_SpinLockMCAS_ZIO
  extends BaseSpecZIO
  with SpecSpinLockMCAS
  with RefSpecJvm[zio.Task]

final class RefSpecJvm_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with RefSpecJvm[IO]

final class RefSpecJvm_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with RefSpecJvm[zio.Task]

final class RefSpecJvm_ThreadConfinedMCAS_ZIO
  extends BaseSpecZIO
  with SpecThreadConfinedMCAS
  with RefSpecJvm[zio.Task]

trait RefSpecJvm[F[_]] extends RefSpec[F] { this: KCASImplSpec =>

  test("version") {
    val p1p1 = Ref.refP1P1("a", "a").unsafeRun(this.kcasImpl)
    val p2 = Ref.refP2("a", "a").unsafeRun(this.kcasImpl)
    val arr = Ref.array(size = 3, initial = "a").unsafeRun(this.kcasImpl)
    val refs = List[MemoryLocation[String]](
      Ref.unsafePadded("a").loc,
      Ref.unsafeUnpadded("a").loc,
      p1p1._1.loc,
      p1p1._2.loc,
      p2._1.loc,
      p2._2.loc,
      arr(0).loc,
      arr(1).loc,
      arr(2).loc,
    )
    refs.traverse { ref =>
      F.delay {
        assertEquals(ref.unsafeGetVersionVolatile(), Long.MinValue)
        assert(ref.unsafeCasVersionVolatile(Long.MinValue, 42L))
        assertEquals(ref.unsafeGetVersionVolatile(), 42L)
      }
    }
  }
}
