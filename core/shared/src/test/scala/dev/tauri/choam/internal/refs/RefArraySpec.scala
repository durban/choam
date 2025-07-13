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

import core.Ref
import internal.mcas.Mcas
import internal.mcas.RefIdGenBase.GAMMA

final class RefArraySpec_Strict extends RefArraySpecIds {

  final override def mkRefArray[A](a: A, size: Int, ctx: Mcas.ThreadContext): Ref.Array[A] =
    Ref.unsafeArray(size, a, Ref.Array.AllocationStrategy(sparse = false, flat = true, padded = false), rig = ctx.refIdGen)
}

final class RefArraySpec_Lazy extends RefArraySpecIds {

  final override def mkRefArray[A](a: A, size: Int, ctx: Mcas.ThreadContext): Ref.Array[A] =
    Ref.unsafeArray(size, a, Ref.Array.AllocationStrategy(sparse = true, flat = true, padded = false), rig = ctx.refIdGen)
}

final class RefArraySpec_StrictArrayOfRefs extends RefArraySpec {

  final override def mkRefArray[A](a: A, size: Int, ctx: Mcas.ThreadContext): Ref.Array[A] =
    Ref.unsafeArray(size, a, Ref.Array.AllocationStrategy(sparse = false, flat = false, padded = false), rig = ctx.refIdGen)
}

final class RefArraySpec_LazyArrayOfRefs extends RefArraySpec {

  final override def mkRefArray[A](a: A, size: Int, ctx: Mcas.ThreadContext): Ref.Array[A] =
    Ref.unsafeArray(size, a, Ref.Array.AllocationStrategy(sparse = true, flat = false, padded = false), rig = ctx.refIdGen)
}

trait RefArraySpecIds extends RefArraySpec {

  // TODO: fix these for the *ArrayOfRefs too

  test("toString format") {
    val arr = mkRefArray("a")
    val arrPat = "Ref\\.Array\\[(\\d+)\\]\\@([\\da-f]+)".r
    val baseHash = arr.toString match {
      case arrPat(size, baseHash) =>
        assertEquals(size, arr.size.toString)
        baseHash
      case other =>
        fail(s"didn't match: '${other}'")
    }
    assertEquals(clue(arr.unsafeGet(0).toString), s"ARef@${baseHash}+0")
    assertEquals(clue(arr.unsafeGet(1).toString), s"ARef@${baseHash}+1")
    assertEquals(clue(arr.unsafeGet(2).toString), s"ARef@${baseHash}+2")
    assertEquals(clue(arr.unsafeGet(3).toString), s"ARef@${baseHash}+3")
  }

  test("equals/toString") {
    val a: Ref.Array[String] = mkRefArray[String]("a")
    val r1: Ref[String] = a.unsafeGet(0)
    val r2: Ref[String] = a.unsafeGet(1)
    assert((a : AnyRef) ne r1)
    assert((a : Any) != r1)
    assert((a : AnyRef) ne r2)
    assert((a : Any) != r2)
    assert(r1 ne r2)
    assert(r1 != r2)
    assert(r1 eq a.unsafeGet(0))
    assert(r1 == a.unsafeGet(0))
    assert(r2 eq a.unsafeGet(1))
    assert(r2 == a.unsafeGet(1))
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
    val str0 = Ref.Array.AllocationStrategy(sparse = false, flat = false, padded = false)
    assert(Ref.array(N, "", str0).unsafePerform(this.defaultMcasInstance).isInstanceOf[Ref.StrictArrayOfRefs[?]])
    val str2 = Ref.Array.AllocationStrategy(sparse = false, flat = true, padded = false)
    assert(Ref.array(N, "", str2).unsafePerform(this.defaultMcasInstance).isInstanceOf[StrictRefArray[?]])
    val str4 = Ref.Array.AllocationStrategy(sparse = true, flat = false, padded = false)
    assert(Ref.array(N, "", str4).unsafePerform(this.defaultMcasInstance).isInstanceOf[Ref.LazyArrayOfRefs[?]])
    val str6 = Ref.Array.AllocationStrategy(sparse = true, flat = true, padded = false)
    assert(Ref.array(N, "", str6).unsafePerform(this.defaultMcasInstance).isInstanceOf[SparseRefArray[?]])
    val str1 = Ref.Array.AllocationStrategy(sparse = false, flat = false, padded = true)
    assert(Ref.array(N, "", str1).unsafePerform(this.defaultMcasInstance).isInstanceOf[Ref.StrictArrayOfRefs[?]])
    assert(Either.catchOnly[IllegalArgumentException] {
      Ref.Array.AllocationStrategy(sparse = false, flat = true, padded = true) // 3
    }.isLeft)
    val str5 = Ref.Array.AllocationStrategy(sparse = true, flat = false, padded = true)
    assert(Ref.array(N, "", str5).unsafePerform(this.defaultMcasInstance).isInstanceOf[Ref.LazyArrayOfRefs[?]])
    assert(Either.catchOnly[IllegalArgumentException] {
      Ref.Array.AllocationStrategy(sparse = true, flat = true, padded = true) // 7
    }.isLeft)
    assert(Ref.unsafeArray(N, "", Ref.Array.AllocationStrategy.SparseFlat, this.mcasImpl.currentContext().refIdGen).isInstanceOf[SparseRefArray[?]])
    assert(Ref.unsafeArray(N, "", Ref.Array.AllocationStrategy.Default, this.mcasImpl.currentContext().refIdGen).isInstanceOf[StrictRefArray[?]])
  }

  test("empty array") {
    val arr = mkRefArray("foo", 0)
    val pat = "Ref\\.Array\\[0\\]\\@[\\da-f]+".r
    assert(pat.matches(clue(arr.toString)))
    assert(Try { arr.unsafeGet(0) }.isFailure)
  }

  test("big array") {
    val size = if (isJvm()) 0x01ffffff else 0x001fffff
    val arr = mkRefArray("foo", size)
    val r = arr.unsafeGet(size / 2)
    r.update { ov => assertEquals(ov, "foo"); "bar" }.unsafePerform(this.defaultMcasInstance)
    r.update { ov => assertEquals(ov, "bar"); "xyz" }.unsafePerform(this.defaultMcasInstance)
    assertSameInstance(r.get.unsafePerform(this.defaultMcasInstance), "xyz")
    if (isJvm()) {
      assert(r.loc.unsafeGetMarkerV() ne null)
    }
    val r2 = arr.unsafeGet(size - 1)
    val res = Ref.consistentRead(r, r2).unsafePerform(this.defaultMcasInstance)
    assertEquals(res, ("xyz", "foo"))
  }

  test("indexing error") {
    def checkError(op: => Ref[String])(implicit loc: Location): Unit = {
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

  test("safe indexing") {
    val arr = mkRefArray("foo", 4)
    assert(arr.apply(Int.MinValue).isEmpty)
    assert(arr.apply(-1).isEmpty)
    assert(arr.apply(0).isDefined)
    assert(arr.apply(1).isDefined)
    assert(arr.apply(2).isDefined)
    assert(arr.apply(3).isDefined)
    assert(arr.apply(4).isEmpty)
    assert(arr.apply(5).isEmpty)
    assert(arr.apply(6).isEmpty)
    assert(arr.apply(1024).isEmpty)
    assert(arr.apply(Int.MaxValue).isEmpty)
  }

  test("consistentRead") {
    val a = mkRefArray[Int](42)
    a.unsafeGet(0).update(_ + 1).unsafePerform(this.defaultMcasInstance)
    val (x, y) = Ref.consistentRead(a.unsafeGet(0), a.unsafeGet(2)).unsafePerform(this.defaultMcasInstance)
    assert(x == 43)
    assert(y == 42)
  }

  test("read/write/cas") {
    val a = mkRefArray[String]("a")
    val r1 = a.unsafeGet(1).loc
    val r2 = a.unsafeGet(2).loc
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
