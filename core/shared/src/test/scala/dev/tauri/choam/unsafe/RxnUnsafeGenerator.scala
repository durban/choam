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
import java.util.concurrent.atomic.AtomicInteger

import scala.runtime.ObjectRef

import internal.mcas.Mcas
import core.{ Rxn, Ref, RxnGenerator }
import RxnUnsafeGenerator.MutSeed

abstract class RxnUnsafeGenerator[F[_]](mcas: Mcas) {

  protected def assertEquals[A](actual: A, expected: A)(implicit loc: munit.Location): Unit
  protected def assert(cond: Boolean)(implicit loc: munit.Location): Unit

  private[this] final val retryChance =
    Integer.MAX_VALUE

  private[this] val pureGen =
    new RxnGenerator(mcas)

  final def generate(
    seed: Long,
    size: Int,
    runner: (InRxn => Any) => F[Any],
    isStm: Boolean = false,
  ): F[Any] = {
    require(size >= 0)
    val os = new MutSeed(seed)
    val is = os.nextLong()
    val block = generateBlock(
      is,
      size = size,
      ref = null,
      retryCtr = new AtomicInteger,
      locals = Nil,
      refs = Nil,
      isStm = isStm,
    )
    runner(block)
  }

  private[this] final def generateBlock(
    is: Long,
    size: Int,
    ref: Ref[Any],
    retryCtr: AtomicInteger,
    locals: List[ObjectRef[Option[Any]]],
    refs: List[ObjectRef[Ref[Any]]],
    isStm: Boolean,
  ): (InRxn => Any) = { implicit u =>
    val s = new MutSeed(is)
    val currRef: ObjectRef[Ref[Any]] = ObjectRef.create(if (ref ne null) ref else newRef[Any](""))
    val refRef: Ref[Ref[Any]] = newRef[Ref[Any]](currRef.elem)
    var lastWrittenToRef: Any = currRef.elem.value
    val local: ObjectRef[Option[Any]] = ObjectRef.create(Some(42))
    val localLocal: RxnLocal[Any] = newLocal(42)
    def maybeRetry(): Unit = {
      val shift = java.lang.Math.min(retryCtr.getAndIncrement(), 0x1f)
      val chance = retryChance >>> shift
      if (s.nextBounded(Integer.MAX_VALUE) < chance) {
        // we'll retry, but we need to reset the
        // state we have here in imperative code:
        local.elem = None
        locals.foreach(_.elem = None)
        currRef.elem = null
        refs.foreach(_.elem = null)
        alwaysRetry()
      }
    }
    def generateOneRxn(): Rxn[Any] = {
      val rxn = pureGen.generate(s.nextLong(), includeTxn = isStm)
      if (isStm) {
        // we include a fallback, because if it suspends, we can't test it:
        Rxn.unsafe.orElse(rxn, Rxn.pure(42))
      } else {
        rxn
      }
    }
    def step(): Unit = {
      currRef.elem match {
        case null =>
          currRef.elem = refRef.value
        case currRef =>
          assertEquals(readRef(currRef), lastWrittenToRef)
      }
      local.elem match {
        case Some(lv) => assertEquals(lv, embedRxn(localLocal.get))
        case None => ()
      }
      assert(refRef.value eq currRef.elem)
      s.nextBounded(7) match {
        case 0 =>
          val ns = s.nextString()
          currRef.elem = newRef[Any](ns)
          val ov = getAndSetRef(refRef, currRef.elem)
          val ovv = ov.value
          assertEquals(ovv, lastWrittenToRef)
          lastWrittenToRef = ns
        case 1 =>
          val lv = local.elem.getOrElse("")
          writeRef(currRef.elem, lv)
          lastWrittenToRef = lv
        case 2 =>
          addPostCommit(generateOneRxn().void)
        case 3 =>
          val lv = embedRxn(generateOneRxn())
          local.elem = Some(lv)
          embedRxn(localLocal.set(lv))
        case 4 =>
          val lv = local.elem.getOrElse("")
          val ov = embedRxn(currRef.elem.getAndSet(lv))
          assertEquals(ov, lastWrittenToRef)
          lastWrittenToRef = lv
        case 5 =>
          val innerBlock = generateBlock(
            s.nextLong(),
            size = size >>> 1,
            ref = currRef.elem,
            retryCtr = retryCtr,
            locals = local :: locals,
            refs = currRef :: refs,
            isStm = isStm,
          )
          val rxn: Rxn[Any] = Rxn.unsafe.embedUnsafe(innerBlock)
          embedRxn(rxn) : Unit
          currRef.elem = refRef.value
          lastWrittenToRef = currRef.elem.value
        case 6 =>
          maybeRetry()
        case _ =>
          assert(false)
      }
    }
    for (_ <- 1 to size) {
      step()
    }
    readRef(currRef.elem)
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
