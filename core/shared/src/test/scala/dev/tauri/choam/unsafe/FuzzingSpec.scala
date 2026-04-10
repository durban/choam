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
package unsafe

import cats.effect.IO

import org.scalacheck.effect.PropF.forAllF
import munit.ScalaCheckEffectSuite

final class FuzzingSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with FuzzingSpec[IO]

trait FuzzingSpec[F[_]] extends BaseSpecAsyncF[F] with ScalaCheckEffectSuite { self: McasImplSpec =>

  val gen = new RxnUnsafeGenerator[F](this.mcasImpl) {
    final override def assertEquals[A](actual: A, expected: A): Unit =
      self.assertEquals(actual, expected)
  }

  test("fuzzing") {
    forAllF { (seed: Long) =>
      gen.generate(seed).map(_ => true)
    }
  }
}
