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
import core.{ Rxn, Ref, RxnGenerator, AsyncReactive }
import RxnUnsafeGenerator.MutSeed

abstract class RxnUnsafeGenerator[F[_]](mcas: Mcas)(implicit F: AsyncReactive[F]) {

  protected def assertEquals[A](actual: A, expected: A): Unit

  private[this] val pureGen = new RxnGenerator(mcas)

  private[this] val rt = ChoamRuntime.forTesting(mcas)

  private[this] val u = UnsafeApi(rt)

  private[this] val runner = List[(InRxn => Any) => F[Any]](
    block => F.asyncInst.delay { u.atomically(block) },
    block => u.atomicallyInAsync[F, Any](RetryStrategy.Default)(block)(using F.asyncInst),
    block => Rxn.unsafe.embedUnsafe(block).run[F],
  )

  final def generate(seed: Long): F[Any] = {
    val os = new MutSeed(seed)
    val run = os.select(runner)
    val is = os.nextLong()
    val block: InRxn => Any = { implicit u =>
      val s = new MutSeed(is)
      var currRef: Ref[Any] = newRef[Any]("")
      var lastWrittenToRef: Any = ""
      var local: Any = 42
      def step(): Unit = {
        assertEquals(readRef(currRef), lastWrittenToRef)
        s.nextBounded(4) match {
          case 0 =>
            val ns = s.nextString()
            currRef = newRef[Any](ns)
            lastWrittenToRef = ns
          case 1 =>
            writeRef(currRef, local)
            lastWrittenToRef = local
          case 2 =>
            addPostCommit(pureGen.generate(s.nextLong()).void)
          case 3 =>
            local = embedRxn(pureGen.generate(s.nextLong()))
        }
      }
      for (_ <- 1 to 10000) {
        step()
      }
      readRef(currRef)
    }

    run(block)
  }
}

object RxnUnsafeGenerator {

  final class MutSeed(rng: SplittableRandom) {

    def this(seed: Long) = this(new SplittableRandom(seed))

    def nextBounded(maxExclusive: Int): Int = {
      rng.nextInt(0, maxExclusive)
    }

    def select[A](as: Seq[A]): A = {
      val idx = this.nextBounded(as.length)
      as(idx)
    }

    def nextLong(): Long = {
      rng.nextLong()
    }

    def nextString(): String = {
      this.nextLong().toString
    }
  }
}
