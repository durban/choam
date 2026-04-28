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

import core.Rxn

final class FuzzingSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with FuzzingSpec[IO]

trait FuzzingSpec[F[_]] extends BaseSpecAsyncF[F] with ScalaCheckEffectSuite { self: McasImplSpec =>

  private[this] val rt = ChoamRuntime.forTesting(this.mcasImpl)

  private[this] val u = UnsafeApi(rt)

  private[this] val gen = new RxnUnsafeGenerator[F](this.mcasImpl) {

    final override def assertEquals[A](actual: A, expected: A)(implicit loc: munit.Location): Unit = {
      self.assertEquals(actual, expected)
    }

    final override def assert(cond: Boolean)(implicit loc: munit.Location): Unit = {
      self.assert(cond)
    }
  }

  private[this] val size = this.platform match {
    case Jvm => 110
    case Js => 80
    case Native => 75
  }

  val atomicallyRunner: (InRxn => Any) => F[Any] =
    block => F.delay { u.atomically(block) }

  val atomicallyInAsyncRunner: (InRxn => Any) => F[Any] =
    block => u.atomicallyInAsync[F, Any](RetryStrategy.Default)(block)(using F)

  val embedUnsafeRunner: (InRxn => Any) => F[Any] =
    block => Rxn.unsafe.embedUnsafe(block).run[F]

  test("fuzzing (atomically)") {
    forAllF { (seed: Long) =>
      gen.generate(seed, size = size, runner = atomicallyRunner).map(_ => true)
    }
  }

  test("fuzzing (atomicallyInAsync)") {
    forAllF { (seed: Long) =>
      gen.generate(seed, size = size, runner = atomicallyInAsyncRunner).map(_ => true)
    }
  }

  test("fuzzing (embedUnsafe)") {
    forAllF { (seed: Long) =>
      gen.generate(seed, size = size, runner = embedUnsafeRunner).map(_ => true)
    }
  }
}
