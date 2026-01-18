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
package internal
package refs

import scala.util.Try

import cats.syntax.all._

import munit.Location

import core.{ Ref, Rxn }
import internal.mcas.{ Mcas, Consts }
import internal.mcas.RefIdGenBase.GAMMA

final class RefArraySpec_Strict extends RefArraySpecIds {

  final override def mkRefArray[A](a: A, size: Int, ctx: Mcas.ThreadContext): Ref.Array[A] =
    Ref.unsafeArray(size, a, AllocationStrategy(sparse = false, flat = true, padded = false), rig = ctx.refIdGen)
}

final class RefArraySpec_Lazy extends RefArraySpecIds {

  final override def mkRefArray[A](a: A, size: Int, ctx: Mcas.ThreadContext): Ref.Array[A] =
    Ref.unsafeArray(size, a, AllocationStrategy(sparse = true, flat = true, padded = false), rig = ctx.refIdGen)
}

final class RefArraySpec_StrictArrayOfRefs extends RefArraySpec {

  final override def mkRefArray[A](a: A, size: Int, ctx: Mcas.ThreadContext): Ref.Array[A] =
    Ref.unsafeArray(size, a, AllocationStrategy(sparse = false, flat = false, padded = false), rig = ctx.refIdGen)
}

final class RefArraySpec_LazyArrayOfRefs extends RefArraySpec {

  final override def mkRefArray[A](a: A, size: Int, ctx: Mcas.ThreadContext): Ref.Array[A] =
    Ref.unsafeArray(size, a, AllocationStrategy(sparse = true, flat = false, padded = false), rig = ctx.refIdGen)
}

trait RefArraySpecIds extends RefArraySpec {

  // TODO: fix these for the *ArrayOfRefs too

  test("toString format") {
    val arr = mkRefArray("a")
    val arrPat = "Ref\\.Array\\[(\\d+)\\]\\@([\\da-f]+)".r
    val baseHash = arr.toString match {
      case arrPat(size, baseHash) =>
        assertEquals(size, arr.length.toString)
        baseHash
      case other =>
        fail(s"didn't match: '${other}'")
    }
    assertEquals(clue(arr.getOrCreateRefOrNull(0).toString), s"ARef@${baseHash}+0")
    assertEquals(clue(arr.getOrCreateRefOrNull(1).toString), s"ARef@${baseHash}+1")
    assertEquals(clue(arr.getOrCreateRefOrNull(2).toString), s"ARef@${baseHash}+2")
    assertEquals(clue(arr.getOrCreateRefOrNull(3).toString), s"ARef@${baseHash}+3")
  }

  test("equals/toString") {
    val a: Ref.Array[String] = mkRefArray[String]("a")
    val r1: Ref[String] = a.getOrCreateRefOrNull(0)
    val r2: Ref[String] = a.getOrCreateRefOrNull(1)
    assert((a : AnyRef) ne r1)
    assert((a : Any) != r1)
    assert((a : AnyRef) ne r2)
    assert((a : Any) != r2)
    assert(r1 ne r2)
    assert(r1 != r2)
    assert(r1 eq a.getOrCreateRefOrNull(0))
    assert(r1 == a.getOrCreateRefOrNull(0))
    assert(r2 eq a.getOrCreateRefOrNull(1))
    assert(r2 == a.getOrCreateRefOrNull(1))
    assert(r1.toString != r2.toString)
    assertEquals(r1.loc.id + GAMMA, r2.loc.id)
  }
}

trait RefArraySpec extends BaseSpec with SpecDefaultMcas {

  final val N = 4

  def mkRefArray[A](a: A, size: Int, ctx: Mcas.ThreadContext): Ref.Array[A]

  final def mkRefArray[A](a: A, size: Int = N): Ref.Array[A] =
    this.mkRefArray(a, size, ctx = this.mcasImpl.currentContext())

  test("array creation") {
    val str0 = AllocationStrategy(sparse = false, flat = false, padded = false)
    assert(Ref.array(N, "", str0).unsafePerform(this.defaultMcasInstance).isInstanceOf[DenseArrayOfRefs[?]])
    val str2 = AllocationStrategy(sparse = false, flat = true, padded = false)
    assert(Ref.array(N, "", str2).unsafePerform(this.defaultMcasInstance).isInstanceOf[DenseRefArray[?]])
    val str4 = AllocationStrategy(sparse = true, flat = false, padded = false)
    assert(Ref.array(N, "", str4).unsafePerform(this.defaultMcasInstance).isInstanceOf[SparseArrayOfRefs[?]])
    val str6 = AllocationStrategy(sparse = true, flat = true, padded = false)
    assert(Ref.array(N, "", str6).unsafePerform(this.defaultMcasInstance).isInstanceOf[SparseRefArray[?]])
    val str1 = AllocationStrategy(sparse = false, flat = false, padded = true)
    assert(Ref.array(N, "", str1).unsafePerform(this.defaultMcasInstance).isInstanceOf[DenseArrayOfRefs[?]])
    assert(Either.catchOnly[IllegalArgumentException] {
      AllocationStrategy(sparse = false, flat = true, padded = true) // 3
    }.isLeft)
    val str5 = AllocationStrategy(sparse = true, flat = false, padded = true)
    assert(Ref.array(N, "", str5).unsafePerform(this.defaultMcasInstance).isInstanceOf[SparseArrayOfRefs[?]])
    assert(Either.catchOnly[IllegalArgumentException] {
      AllocationStrategy(sparse = true, flat = true, padded = true) // 7
    }.isLeft)
    assert(Ref.unsafeArray(N, "", AllocationStrategy.SparseFlat, this.mcasImpl.currentContext().refIdGen).isInstanceOf[SparseRefArray[?]])
    assert(Ref.unsafeArray(N, "", AllocationStrategy.Default, this.mcasImpl.currentContext().refIdGen).isInstanceOf[DenseRefArray[?]])
  }

  test("empty array") {
    val arr = mkRefArray("foo", 0)
    val pat = "Ref\\.Array\\[0\\]\\@[\\da-f]+".r
    assert(pat.matches(clue(arr.toString)))
    assert(Try { arr.unsafeGet(0) }.isFailure)
  }

  test("big array") {
    val size = this.platform match {
      case Jvm => 0x01ffffff
      case Js => 0x001fffff
      case Native => 0x3fffff
    }
    val arr = mkRefArray("foo", size)
    val r = arr.getOrCreateRefOrNull(size / 2)
    r.update { ov => assertEquals(ov, "foo"); "bar" }.unsafePerform(this.defaultMcasInstance)
    r.update { ov => assertEquals(ov, "bar"); "xyz" }.unsafePerform(this.defaultMcasInstance)
    assertSameInstance(r.get.unsafePerform(this.defaultMcasInstance), "xyz")
    if (isMultithreaded()) {
      assert(r.loc.unsafeGetMarkerV() ne null)
    }
    val r2 = arr.getOrCreateRefOrNull(size - 1)
    val res = Ref.consistentRead(r, r2).unsafePerform(this.defaultMcasInstance)
    assertEquals(res, ("xyz", "foo"))
  }

  test("indexing error") {
    def checkError(op: => Rxn[String])(implicit loc: Location): Unit = {
      val ok = Try(op).failed.get.isInstanceOf[IndexOutOfBoundsException]
      assert(ok)
    }
    val arr1 = mkRefArray("foo", 4) // even
    checkError { arr1.unsafeGet(4) }
    checkError { arr1.unsafeGet(5) }
    checkError { arr1.unsafeGet(-1) }
    checkError { arr1.unsafeGet(Int.MinValue) }
    checkError { arr1.unsafeGet(Int.MaxValue) }
    val arr2 = mkRefArray("foo", 5) // odd
    checkError { arr2.unsafeGet(5) }
    checkError { arr2.unsafeGet(6) }
    checkError { arr2.unsafeGet(-1) }
    checkError { arr2.unsafeGet(Int.MinValue) }
    checkError { arr2.unsafeGet(Int.MaxValue) }
  }

  // TODO: reenable this test:
  // test("safe indexing") {
  //   val arr = mkRefArray("foo", 4)
  //   assert(arr.apply(Int.MinValue).isEmpty)
  //   assert(arr.apply(-1).isEmpty)
  //   assert(arr.apply(0).isDefined)
  //   assert(arr.apply(1).isDefined)
  //   assert(arr.apply(2).isDefined)
  //   assert(arr.apply(3).isDefined)
  //   assert(arr.apply(4).isEmpty)
  //   assert(arr.apply(5).isEmpty)
  //   assert(arr.apply(6).isEmpty)
  //   assert(arr.apply(1024).isEmpty)
  //   assert(arr.apply(Int.MaxValue).isEmpty)
  // }

  test("unsafeSwap") {
    val arr1 = mkRefArray("foo", 2)
    arr1.unsafeSet(0, "bar").unsafePerform(this.mcasImpl)
    val arr2 = mkRefArray("xyz", 2)
    Ref.Array.unsafeSwap(arr1, 0, arr2, 1).unsafePerform(this.mcasImpl)
    assertEquals(arr1.unsafeGet(0).unsafePerform(this.mcasImpl), "xyz")
    assertEquals(arr2.unsafeGet(1).unsafePerform(this.mcasImpl), "bar")
    assertEquals(arr2.unsafeGet(0).unsafePerform(this.mcasImpl), "xyz")
  }

  test("unsafeGet") {
    val arr = mkRefArray("foo", 2)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "foo")
    arr.unsafeSet(1, "bar").unsafePerform(this.mcasImpl)
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar")
    assert(Either.catchNonFatal(arr.unsafeGet(2)).isLeft)
    assert(Either.catchNonFatal(arr.unsafeGet(-1)).isLeft)
  }

  test("unsafeSet") {
    val arr = mkRefArray("foo", 2)
    arr.unsafeSet(0, "bar0").unsafePerform(this.mcasImpl)
    arr.unsafeSet(1, "bar1").unsafePerform(this.mcasImpl)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
    assert(Either.catchNonFatal(arr.unsafeSet(2, "")).isLeft)
    assert(Either.catchNonFatal(arr.unsafeSet(-1, "")).isLeft)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
  }

  test("unsafeUpdate") {
    val arr = mkRefArray("foo", 2)
    arr.unsafeUpdate(0) { ov =>
      assertEquals(ov, "foo")
      "bar0"
    }.unsafePerform(this.mcasImpl)
    arr.unsafeUpdate(1) { ov =>
      assertEquals(ov, "foo")
      "bar1"
    }.unsafePerform(this.mcasImpl)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
    assert(Either.catchNonFatal(arr.unsafeUpdate(2)(_ => "")).isLeft)
    assert(Either.catchNonFatal(arr.unsafeUpdate(-1)(_ => "")).isLeft)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
  }

  test("unsafeModify") {
    val arr = mkRefArray("foo", 2)
    val r0 = arr.unsafeModify(0) { ov =>
      assertEquals(ov, "foo")
      ("bar0", 42)
    }.unsafePerform(this.mcasImpl)
    assertEquals(r0, 42)
    val r1 = arr.unsafeModify(1) { ov =>
      assertEquals(ov, "foo")
      ("bar1", 43)
    }.unsafePerform(this.mcasImpl)
    assertEquals(r1, 43)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
    assert(Either.catchNonFatal(arr.unsafeModify(2)(_ => ("", -1))).isLeft)
    assert(Either.catchNonFatal(arr.unsafeModify(-1)(_ => ("", -1))).isLeft)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
  }

  test("get") {
    val arr = mkRefArray("foo", 2)
    assertEquals(arr.get(0).unsafePerform(this.mcasImpl), Some("foo"))
    arr.unsafeSet(1, "bar").unsafePerform(this.mcasImpl)
    assertEquals(arr.get(1).unsafePerform(this.mcasImpl), Some("bar"))
    assertEquals(arr.get(2).unsafePerform(this.mcasImpl), None)
    assertEquals(arr.get(-1).unsafePerform(this.mcasImpl), None)
  }

  test("set") {
    val arr = mkRefArray("foo", 2)
    assertEquals(arr.set(0, "bar0").unsafePerform(this.mcasImpl), true)
    assertEquals(arr.set(1, "bar1").unsafePerform(this.mcasImpl), true)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
    assertEquals(arr.set(2, "").unsafePerform(this.mcasImpl), false)
    assertEquals(arr.set(-1, "").unsafePerform(this.mcasImpl), false)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
  }

  test("update") {
    val arr = mkRefArray("foo", 2)
    val r0 = arr.update(0) { ov =>
      assertEquals(ov, "foo")
      "bar0"
    }.unsafePerform(this.mcasImpl)
    assertEquals(r0, true)
    val r1 = arr.update(1) { ov =>
      assertEquals(ov, "foo")
      "bar1"
    }.unsafePerform(this.mcasImpl)
    assertEquals(r1, true)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
    assertEquals(arr.update(2)(_ => "").unsafePerform(this.mcasImpl), false)
    assertEquals(arr.update(-1)(_ => "").unsafePerform(this.mcasImpl), false)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
  }

  test("modify") {
    val arr = mkRefArray("foo", 2)
    val r0 = arr.modify(0) { ov =>
      assertEquals(ov, "foo")
      ("bar0", 42)
    }.unsafePerform(this.mcasImpl)
    assertEquals(r0, Some(42))
    val r1 = arr.modify(1) { ov =>
      assertEquals(ov, "foo")
      ("bar1", 43)
    }.unsafePerform(this.mcasImpl)
    assertEquals(r1, Some(43))
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
    assertEquals(arr.modify(2)(_ => ("", -1)).unsafePerform(this.mcasImpl), None)
    assertEquals(arr.modify(-1)(_ => ("", -1)).unsafePerform(this.mcasImpl), None)
    assertEquals(arr.unsafeGet(0).unsafePerform(this.mcasImpl), "bar0")
    assertEquals(arr.unsafeGet(1).unsafePerform(this.mcasImpl), "bar1")
  }

  test("refs") {
    val a0 = mkRefArray("foo", 0)
    assertEquals(a0.refs : IndexedSeq[Ref[String]], IndexedSeq.empty)
    val a1 = mkRefArray("foo", 1)
    assertEquals(a1.refs.length, 1)
    val r10 = a1.refs(0)
    assertEquals(r10.getAndSet("bar").unsafePerform(this.mcasImpl), "foo")
    assertSameInstance(a1.refs.headOption.getOrElse(fail("missing ref")), r10)
    assertEquals(r10.get.unsafePerform(this.mcasImpl), "bar")
    assert(Either.catchNonFatal { a1.refs(1) }.isLeft)
    val a2 = mkRefArray("foo", 3)
    a2.refs(1).set("bar").unsafePerform(this.mcasImpl)
    val vs = a2.refs.foldLeft(Rxn.pure("")) { (acc, ref) =>
      ref.get.flatMap { s => acc.map(_ + s) }
    }.unsafePerform(this.mcasImpl)
    assertEquals(vs, "foobarfoo")
  }

  test("refs (STM)") {
    val a0 = Ref.safeTArrayImpl(0, "foo", stm.TArray.DefaultAllocationStrategy).unsafePerform(this.mcasImpl)
    assertEquals(a0.refs : IndexedSeq[Ref[String]], IndexedSeq.empty)
    val a1 = Ref.safeTArrayImpl(1, "foo", stm.TArray.DefaultAllocationStrategy).unsafePerform(this.mcasImpl)
    assertEquals(a1.refs.length, 1)
    val r10: Ref[String] = a1.refs(0)
    r10 match {
      case _: stm.TRef[_] => // ok
      case _ => fail("expected a TRef")
    }
    var listenerCalled = 0
    val id = r10.loc.withListeners.unsafeRegisterListener(
      this.mcasImpl.currentContext(),
      { _ => listenerCalled += 1 },
      this.mcasImpl.currentContext().readVersion(r10.loc),
    )
    assertNotEquals(id, Consts.InvalidListenerId)
    assertEquals(listenerCalled, 0)
    assertEquals(r10.getAndSet("bar").unsafePerform(this.mcasImpl), "foo")
    assertEquals(listenerCalled, 1)
  }

  test("consistentRead") {
    val a = mkRefArray[Int](42)
    a.unsafeUpdate(0)(_ + 1).unsafePerform(this.defaultMcasInstance)
    val (x, y) = Ref.Array.unsafeConsistentRead(a, 0, a, 2).unsafePerform(this.defaultMcasInstance)
    assert(x == 43)
    assert(y == 42)
  }

  test("read/write/cas") {
    val a = mkRefArray[String]("a")
    val r1 = a.getOrCreateRefOrNull(1).loc
    val r2 = a.getOrCreateRefOrNull(2).loc
    assert(r1.unsafeGetV() eq "a")
    assert(r2.unsafeGetV() eq "a")
    r1.unsafeSetV("b")
    assert(r1.unsafeGetV() eq "b")
    assert(r2.unsafeGetV() eq "a")
    r2.unsafeSetV("x")
    assert(r1.unsafeGetV() eq "b")
    assert(r2.unsafeGetV() eq "x")
    assert(r1.unsafeCasV("b", "c"))
    assert(r1.unsafeGetV() eq "c")
    assert(r2.unsafeGetV() eq "x")
    assert(!r2.unsafeCasV("-", "+"))
    assert(r1.unsafeGetV() eq "c")
    assert(r2.unsafeGetV() eq "x")
  }
}
