/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO

import core.{ Rxn, Axn, Ref }
import dev.tauri.choam.core.RetryStrategy

final class EmbedUnsafeSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with EmbedUnsafeSpec[IO]

trait EmbedUnsafeSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  test("embedUnsafe") {
    for {
      ref1 <- Ref(0).run[F]
      ref2 <- Ref(0).run[F]
      rxn = ref1.get.flatMapF { v1 =>
        ref1.set1(v1 + 1) *> Rxn.unsafe.embedUnsafe[Unit] { implicit ir =>
          assertEquals(ref1.value, 1)
          assertEquals(ref2.value, 0)
          ref1.value = 42
          ref2.value = 99
        } *> (ref1.get, ref2.get).tupled
      }
      _ <- assertResultF(rxn.run[F], (42, 99))
      _ <- assertResultF(ref1.get.run, 42)
      _ <- assertResultF(ref2.get.run, 99)
    } yield ()
  }

  test("embedAxn") {
    def getAndIncrBoth(ref1: Ref[Int], ref2: Ref[Int]): Axn[(Int, Int)] = {
      (ref1.get, ref2.get).flatMapN { (v1, v2) =>
        ref1.set1(v1 + 1) *> ref2.set1(v2 + 1).as((v1, v2))
      }
    }
    for {
      ref1 <- Ref(0).run[F]
      ref2 <- Ref(0).run[F]
      rxn = ref1.get.flatMapF { v1 =>
        ref1.set1(v1 + 1) *> Rxn.unsafe.embedUnsafe[Unit] { implicit ir =>
          assertEquals(ref1.value, 1)
          assertEquals(ref2.value, 0)
          ref1.value = 42
          ref2.value = 99
          val (m1, m2) = Rxn.unsafe.embedAxn(getAndIncrBoth(ref1, ref2))
          assertEquals(m1, 42)
          assertEquals(m2, 99)
          assertEquals(ref1.value, 43)
          assertEquals(ref2.value, 100)
          ref1.value = 44
          ref2.value = 101
        } *> (ref1.get, ref2.get).tupled
      }
      _ <- assertResultF(rxn.run[F], (44, 101))
      _ <- assertResultF(ref1.get.run, 44)
      _ <- assertResultF(ref2.get.run, 101)
    } yield ()
  }

  test("Calling embedAxn incorrectly") {
    val api = UnsafeApi(this.runtime)
    val axn = Axn.pure(42)
    for {
      ctr <- F.delay(new AtomicInteger)
      _ <- assertRaisesF(F.delay {
        api.atomically { implicit ir =>
          val i: Int = Rxn.unsafe.embedAxn(axn)
          ctr.incrementAndGet()
          i + 1
        }
      }, _.isInstanceOf[Throwable])
      _ <- assertResultF(F.delay(ctr.get()), 0)
      _ <- assertRaisesF(api.atomicallyAsync[F, Int](RetryStrategy.Default.withCede(true)) { implicit ir =>
        val i: Int = Rxn.unsafe.embedAxn(axn)
        ctr.incrementAndGet()
        i + 1
      } (F), _.isInstanceOf[Throwable])
      _ <- assertResultF(F.delay(ctr.get()), 0)
    } yield ()
  }

  test("Nesting") {
    def layer0(ref1: Ref[Int], ref2: Ref[String], ctr0: AtomicInteger): Axn[(Int, String)] = {
      Rxn.unsafe.embedUnsafe { implicit ir =>
        ctr0.getAndIncrement()
        val ov1 = ref1.value
        val ov2 = ref2.value
        ref1.value = ov1 + 1
        ref2.value = ov2 + "layer0"
        (ov1, ov2)
      }
    }
    def layer1(ref1: Ref[Int], ref2: Ref[String], ctr0: AtomicInteger, ctr1: AtomicInteger): Axn[(Int, String, Int, String)] = {
      Rxn.unsafe.embedUnsafe { implicit ir =>
        ctr1.getAndIncrement()
        val ov1 = ref1.value
        val ov2 = ref2.value
        ref1.value = ov1 + 1
        ref2.value = ov2 + "layer1"
        val (eov1, eov2) = Rxn.unsafe.embedAxn(layer0(ref1, ref2, ctr0))
        assertEquals(eov1, ov1 + 1)
        assertEquals(eov2, ov2 + "layer1")
        assertEquals(ref1.value, ov1 + 1 + 1)
        assertEquals(ref2.value, ov2 + "layer1" + "layer0")
        updateRef(ref1)(_ + 1)
        updateRef(ref2)(_ + "layer1again")
        (ov1, ov2, eov1, eov2)
      }
    }
    def layer2(ctr0: AtomicInteger, ctr1: AtomicInteger): Axn[Int] = {
      Ref[Int](0).flatMapF { ref1 =>
        Rxn.unsafe.embedUnsafe { implicit ir =>
          newRef("")
        }.flatMapF { ref2 =>
          ref1.update1(_ + 1) *> ref2.update1(_ + "layer2") *> layer1(ref1, ref2, ctr0, ctr1).flatMapF {
            case (ov1, ov2, eov1, eov2) =>
              Axn.unsafe.delay {
                assertEquals(ov1, 1)
                assertEquals(ov2, "layer2")
                assertEquals(eov1, 2)
                assertEquals(eov2, "layer2layer1")
                assertEquals(ctr0.get(), 1)
                assertEquals(ctr1.get(), 1)
              } *> Rxn.unsafe.embedUnsafe { implicit ir =>
                assertEquals(ref1.value, 4)
                assertEquals(ref2.value, "layer2layer1layer0layer1again")
                42
              }
          }
        }
      }
    }
    for {
      ctr01 <- F.delay(new AtomicInteger)
      ctr11 <- F.delay(new AtomicInteger)
      _ <- assertResultF(layer2(ctr01, ctr11).run[F], 42)
      ctr02 <- F.delay(new AtomicInteger)
      ctr12 <- F.delay(new AtomicInteger)
      _ <- assertResultF(layer2(ctr02, ctr12).run[F], 42)
    } yield ()
  }
}
