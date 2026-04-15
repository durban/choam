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

import java.util.SplittableRandom

import internal.mcas.Mcas
import core.{ Rxn, Ref, RxnGenerator }
import RxnUnsafeGenerator.MutSeed

abstract class RxnUnsafeGenerator[F[_]](mcas: Mcas) {

  protected def assertEquals[A](actual: A, expected: A): Unit
  protected def assert(cond: Boolean): Unit

  private[this] val pureGen =
    new RxnGenerator(mcas)

  final def generate(
    seed: Long,
    size: Int,
    runner: (InRxn => Any) => F[Any]
  ): F[Any] = {
    val os = new MutSeed(seed)
    val is = os.nextLong()
    val block = generateBlock(is, size = size)
    runner(block)
  }

  private[this] final def generateBlock(
    is: Long,
    size: Int,
  ): (InRxn => Any) = { implicit u =>
    val s = new MutSeed(is)
    var currRef: Ref[Any] = newRef[Any]("")
    val refRef: Ref[Ref[Any]] = newRef[Ref[Any]](currRef)
    var lastWrittenToRef: Any = ""
    var local: Any = 42
    val localLocal: RxnLocal[Any] = newLocal(42)
    def step(): Unit = {
      assertEquals(readRef(currRef), lastWrittenToRef)
      assertEquals(local, embedRxn(localLocal.get))
      assert(refRef.value eq currRef)
      s.nextBounded(4) match {
        case 0 =>
          val ns = s.nextString()
          currRef = newRef[Any](ns)
          val ov = getAndSetRef(refRef, currRef)
          assertEquals(ov.value, lastWrittenToRef)
          lastWrittenToRef = ns
        case 1 =>
          writeRef(currRef, local)
          lastWrittenToRef = local
        case 2 =>
          addPostCommit(pureGen.generate(s.nextLong()).void)
        case 3 =>
          local = embedRxn(pureGen.generate(s.nextLong()))
          embedRxn(localLocal.set(local))
        case 4 =>
          val ov = embedRxn(currRef.getAndSet(local))
          assertEquals(ov, lastWrittenToRef)
          lastWrittenToRef = local
        case 5 =>
          val ex = embedRxn(Rxn.unsafe.exchanger[Int, String])
          val opt = embedRxn(ex.dual.exchange("foo").?)
          assert(opt.isEmpty)
      }
    }
    for (_ <- 1 to size) {
      step()
    }
    readRef(currRef)
  }
}

object RxnUnsafeGenerator {

  final class MutSeed(seed: Long) {

    private[this] val rng: SplittableRandom =
      new SplittableRandom(seed)

    final def nextBounded(maxExclusive: Int): Int = {
      rng.nextInt(0, maxExclusive)
    }

    final def select[A](as: Seq[A]): A = {
      val idx = this.nextBounded(as.length)
      as(idx)
    }

    final def nextLong(): Long = {
      rng.nextLong()
    }

    final def nextString(): String = {
      this.nextLong().toString
    }
  }
}
