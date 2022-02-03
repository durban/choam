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

import cats.effect.IO

final class TreiberStackSpecThreadConfinedMCAS
  extends BaseSpecIO
  with TreiberStackSpec[IO]
  with SpecThreadConfinedMCAS

trait TreiberStackSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  test("TreiberStack should include the elements passed to its constructor") {
    for {
      s1 <- TreiberStack.fromList[F, Int](Nil)
      _ <- assertResultF(F.delay(s1.unsafeToList(this.kcasImpl)), Nil)
      s2 <- TreiberStack.fromList[F, Int](List(1, 2, 3))
      _ <- assertResultF(F.delay(s2.unsafeToList(this.kcasImpl)), List(3, 2, 1))
    } yield ()
  }

  test("TreiberStack should be a stack") {
    val s = TreiberStack[Int].unsafePerform(null, this.kcasImpl)
    s.push.unsafePerform(1, this.kcasImpl)
    s.push.unsafePerform(2, this.kcasImpl)
    assertEquals(s.tryPop.unsafeRun(this.kcasImpl), Some(2))
    assertEquals(s.tryPop.unsafeRun(this.kcasImpl), Some(1))
    assertEquals(s.tryPop.unsafeRun(this.kcasImpl), None)
  }
}
