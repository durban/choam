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

package com.example.choamtest

import cats.effect.{ Sync, IO }

import munit.{ Location, CatsEffectSuite }

import dev.tauri.choam.mcas.Mcas
import dev.tauri.choam.{ Reactive, Ref }
import dev.tauri.choam.{ BaseSpecF, McasImplSpec }

final class CoreSpec extends CatsEffectSuite with BaseSpecF[MyIO] with McasImplSpec {

  override implicit def rF: Reactive[MyIO] =
    MyIO.asyncReactiveForMyIO

  override def F: Sync[MyIO] =
    MyIO.syncForMyIO

  override protected def absolutelyUnsafeRunSync[A](fa: MyIO[A]): A =
    throw new NotImplementedError

  override def assertResultF[A, B](obtained: MyIO[A], expected: B, clue: String)(implicit loc: Location, ev: B <:< A): MyIO[Unit] = {
    MyIO(obtained.impl.flatMap { r =>
      IO { this.assertEquals(r, expected, clue)(loc, ev) }
    })
  }

  override protected def mcasImpl: Mcas =
    Mcas.DefaultMcas

  override def munitValueTransforms: List[ValueTransform] = {
    new ValueTransform(
      "MyIO",
      {
        case task: MyIO[a] =>
          task.impl.unsafeToFuture()
      }
    ) +: super.munitValueTransforms
  }

  test("Test") {
    val rxn = Ref[String]("foo").flatMapF { ref =>
      ref.updateAndGet(_ + "bar")
    }
    assertResultF(rxn.run[MyIO], "foobar")
  }
}
