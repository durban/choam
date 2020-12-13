/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

import scala.concurrent.ExecutionContext

import cats.effect.IO

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalactic.TypeCheckedTripleEquals

class CASNSpec
  extends AnyFlatSpec
  with Matchers
  with TypeCheckedTripleEquals
  with IOSpec {

  import CASN._

  sealed trait Obj
  final case object A extends Obj
  final case object B extends Obj
  final case object C extends Obj

  implicit val ec: ExecutionContext =
    ExecutionContext.global

  "RDCSS" should "succeed if old values match" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk[Obj](A)
    val d = RDCSSDesc(r1, "r1", r2, A, B)
    val res = RDCSS(d)
    assert(res === AcquireSuccess)
    r1.unsafeTryRead() shouldBe theSameInstanceAs ("r1")
    r2.unsafeTryRead() shouldBe theSameInstanceAs (B)
  }

  it should "fail if o1 doesn't match" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk[Obj](A)
    val d = RDCSSDesc(r1, "x", r2, A, B)
    val res = RDCSS(d)
    assert(res === AcquireSuccess)
    r1.unsafeTryRead() shouldBe theSameInstanceAs ("r1")
    r2.unsafeTryRead() shouldBe theSameInstanceAs (A)
  }

  it should "fail if o2 doesn't match" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk[Obj](A)
    val d = RDCSSDesc(r1, "r1", r2, B, C)
    val res = RDCSS(d)
    assert(res === AcquireFailure)
    r1.unsafeTryRead() shouldBe theSameInstanceAs ("r1")
    r2.unsafeTryRead() shouldBe theSameInstanceAs (A)
  }

  it should "fail if neither match" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk[Obj](A)
    val d = RDCSSDesc(r1, "x", r2, B, C)
    val res = RDCSS(d)
    assert(res === AcquireFailure)
    r1.unsafeTryRead() shouldBe theSameInstanceAs ("r1")
    r2.unsafeTryRead() shouldBe theSameInstanceAs (A)
  }

  private def polluteTheHeap[A](desc: AnyRef): A =
    desc.asInstanceOf[A]

  it should "return the CASN descriptor if there is one" in {
    val casnDesc = CASNDesc(Nil)
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk[Obj](polluteTheHeap[Obj](casnDesc))
    val d = RDCSSDesc(r1, "r1", r2, A, B)
    val res = RDCSS(d)
    assert(res === OtherDescriptor(casnDesc))
    r1.unsafeTryRead() shouldBe theSameInstanceAs ("r1")
    r2.unsafeTryRead() shouldBe theSameInstanceAs (casnDesc)
  }

  it should "return the CASN descriptor if there is one, even if o1 doesn't match" in {
    val casnDesc = CASNDesc(Nil)
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk[Obj](polluteTheHeap[Obj](casnDesc))
    val d = RDCSSDesc(r1, "x", r2, A, B)
    val res = RDCSS(d)
    assert(res === OtherDescriptor(casnDesc))
    r1.unsafeTryRead() shouldBe theSameInstanceAs ("r1")
    r2.unsafeTryRead() shouldBe theSameInstanceAs (casnDesc)
  }

  it should "be atomic" in {
    val r1 = Ref.mk("go")
    val r2 = Ref.mk[Integer](0)

    @tailrec
    def modify(f: Int => Int): Unit = {
      val o2 = RDCSSRead(r2)
      val res = RDCSS(RDCSSDesc[String, Integer](r1, "go", r2, o2, f(o2)))
      if (res !== AcquireSuccess) modify(f) // retry
    }

    @tailrec
    def repeat(times: Int)(block: => Unit): Unit = {
      if (times > 0) {
        val _: Unit = block
        repeat(times - 1)(block)
      }
    }

    def stop(): Unit = {
      assert(r1.unsafeTryPerformCas("go", "stop"))
      ()
    }

    val tsk = for {
      f1 <- IO(repeat(10000)(modify(_ + 1))).start
      f2 <- IO(repeat(10000)(modify(_ - 1))).start
      _ <- f1.join
      _ <- f2.join
      _ = assert(RDCSSRead(r2).intValue === 0)
      _ <- IO(stop())
      _ <- IO(repeat(10000)(modify(_ + 1)))
      _ = assert(RDCSSRead(r2).intValue === 0)
    } yield ()
    tsk.unsafeRunSync()
  }

  "RDCSSRead" should "help perform the operation" in {
    val flag = Ref.mk("go")
    val ref = Ref.mk[Obj](A)
    val desc = RDCSSDesc(flag, "go", ref, A, B)
    ref.unsafeSet(polluteTheHeap[Obj](desc))
    ref.unsafeTryRead() shouldBe theSameInstanceAs (desc)
    val res = RDCSSRead(ref)
    assert(res === B)
    ref.unsafeTryRead() shouldBe theSameInstanceAs (B)
  }

  it should "do a rollback, if the other operation requires it" in {
    val flag = Ref.mk("stop")
    val ref = Ref.mk[Obj](A)
    val desc = RDCSSDesc(flag, "go", ref, A, B)
    ref.unsafeSet(polluteTheHeap[Obj](desc))
    ref.unsafeTryRead() shouldBe theSameInstanceAs (desc)
    val res = RDCSSRead(ref)
    assert(res === A)
    ref.unsafeTryRead() shouldBe theSameInstanceAs (A)
  }

  "CASNRead" should "help the other operation" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk("r2")
    val other = CASNDesc(CASD(r1, "r1", "x") :: CASD(r2, "r2", "y") :: Nil)
    r1.unsafeSet(polluteTheHeap[String](other))
    val res = CASNRead(r1)
    res should === ("x")
    r1.unsafeTryRead() should === ("x")
    r2.unsafeTryRead() should === ("y")
  }

  it should "roll back the other op if necessary" in {
    val r1 = Ref.mk("r1")
    val r2 = Ref.mk("r2")
    val other = CASNDesc(CASD(r1, "r1", "x") :: CASD(r2, "zzz", "y") :: Nil)
    r1.unsafeSet(polluteTheHeap[String](other))
    val res = CASNRead(r1)
    res should === ("r1")
    r1.unsafeTryRead() should === ("r1")
    r2.unsafeTryRead() should === ("r2")
  }
}
