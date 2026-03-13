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

import core.Rxn

final class AtomicallySpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with AtomicallySpec[IO]

trait AtomicallySpec[F[_]] extends UnsafeApiSpecBase[F] { this: McasImplSpec =>

  final override def runBlock[A](block: InRxn => A): F[A] = {
    F.delay {
      api.atomically(block)
    }
  }

  final override def runRoBlock[A](block: InRoRxn => A): F[A] = {
    F.delay {
      api.atomicallyReadOnly(block)
    }
  }

  test("atomicallyWithAlts") {
    val ref = api.atomically { implicit ir => newRef("foo") }
    val res = api.atomicallyWithAlts(
      { implicit ir =>
        ref.value = "bar"
        alwaysRetry() : Unit
        impossible("unreachable")
      },
      ref.set("xyz").as(42),
      ref.set("-").map { _ =>
        impossible("should never be executed") : Unit
        99
      },
    )
    assertEquals(res, 42)
    assertEquals(api.atomicallyWithAlts(readRef(ref)(using _)), "xyz")
  }

  test("embedRxn") { // TODO: move to CommonImperativeApiSpec
    val ref1 = api.atomically { implicit ir => newRef("foo") }
    val ref2 = api.atomically { implicit ir => newRef("x") }
    val res = api.atomically { implicit ir =>
      ref1.value = "bar"
      val ov2 = embedRxn(ref2.getAndSet("y"))
      (ov2, ref1.value)
    }
    assertEquals(res, ("x", "bar"))
    assertEquals(api.atomically { implicit u => ref1.value }, "bar")
    assertEquals(api.atomically { implicit u => ref2.value }, "y")
  }

  test("embedRxn with retry - 1") { // TODO: move to CommonImperativeApiSpec
    val ref1 = api.atomically { implicit ir => newRef("foo") }
    val ref2 = api.atomically { implicit ir => newRef("x") }
    val res = api.atomicallyWithAlts(
      { implicit ir =>
        ref1.value = "bar"
        val ov2 = embedRxn(ref2.getAndSet("y") *> Rxn.unsafe.retry[AnyRef]) // CCE
        (ov2, ref1.value)
      },
      Rxn.pure(("", ""))
    )
    assertEquals(res, ("", ""))
    assertEquals(api.atomically { implicit u => ref1.value }, "foo")
    assertEquals(api.atomically { implicit u => ref2.value }, "x")
  }
}
