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
import dev.tauri.choam.mcas.{ MemoryLocation, Version }

final class RefSpecJvm_Real_SpinLockMCAS_IO
  extends BaseSpecIO
  with SpecSpinLockMCAS
  with RefSpecJvm_Real[IO]

final class RefSpecJvm_Real_SpinLockMCAS_ZIO
  extends BaseSpecZIO
  with SpecSpinLockMCAS
  with RefSpecJvm_Real[zio.Task]

final class RefSpecJvm_Real_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with RefSpecJvm_Real[IO]

final class RefSpecJvm_Real_Emcas_ZIO
  extends BaseSpecZIO
  with SpecEmcas
  with RefSpecJvm_Real[zio.Task]

final class RefSpecJvm_Real_ThreadConfinedMCAS_ZIO
  extends BaseSpecZIO
  with SpecThreadConfinedMCAS
  with RefSpecJvm_Real[zio.Task]

final class RefSpecJvm_Arr_Emcas_IO
  extends BaseSpecIO
  with SpecEmcas
  with RefSpecJvm_Arr[IO]

final class RefSpecJvm_Ref2_EMCAS_IO
  extends BaseSpecIO
  with SpecEmcas
  with RefSpecJvm_Ref2[IO]

trait RefSpecJvm_Arr[F[_]] extends RefLikeSpecJvm[F] with RefSpec_Arr[F] { this: KCASImplSpec =>
}

trait RefSpecJvm_Ref2[F[_]] extends RefLikeSpecJvm[F] with RefSpec_Ref2[F] { this: KCASImplSpec =>
}

trait RefSpecJvm_Real[F[_]] extends RefLikeSpecJvm[F] with RefSpec_Real[F] { this: KCASImplSpec =>
}

trait RefLikeSpecJvm[F[_]] extends RefLikeSpec[F] { this: KCASImplSpec =>

  test("version") {
    val p1p1 = Ref.refP1P1("a", "a").unsafeRun(this.kcasImpl)
    val p2 = Ref.refP2("a", "a").unsafeRun(this.kcasImpl)
    val arr = Ref.array(size = 3, initial = "a").unsafeRun(this.kcasImpl)
    val refs = List[MemoryLocation[String]](
      MemoryLocation.unsafePadded("a"),
      MemoryLocation.unsafeUnpadded("a"),
      Ref.unsafePadded("a").loc,
      Ref.unsafeUnpadded("a").loc,
      p1p1._1.loc,
      p1p1._2.loc,
      p2._1.loc,
      p2._2.loc,
      arr.unsafeGet(0).loc,
      arr.unsafeGet(1).loc,
      arr.unsafeGet(2).loc,
    )
    refs.traverse { ref =>
      F.delay {
        assertEquals(ref.unsafeGetVersionVolatile(), Version.Start)
        assert(ref.unsafeCasVersionVolatile(Version.Start, 42L))
        assertEquals(ref.unsafeGetVersionVolatile(), 42L)
      }
    }
  }
}
