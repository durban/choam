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
package internal

import scala.scalanative.annotation.alwaysinline

import munit.{ FunSuite, Location }

final class AtomicHandleSpec extends FunSuite {

  test("AtomicHandle/AtomicLongHandle/AtomicIntHandle") {
    val msc1 = new MySubClass
    val msc2 = new MySubClass
    val x = new MyClass2(1, 2, 3, msc1, 0L)
    // set:
    checkState(x, 1, 2, 3, msc1, 0L)
    x.setAR(4)
    checkState(x, 1, 2, 4, msc1, 0L)
    x.setBR(msc2)
    checkState(x, 1, 2, 4, msc2, 0L)
    x.setNR(-1L)
    checkState(x, 1, 2, 4, msc2, -1L)
    x.setIR(9)
    checkState(x, 9, 2, 4, msc2, -1L)
    x.setJR(9)
    checkState(x, 9, 9, 4, msc2, -1L)
    // cas:
    assertEquals(x.casA(42, 99), false)
    checkState(x, 9, 9, 4, msc2, -1L)
    assertEquals(x.casA(4, 99), true)
    checkState(x, 9, 9, 99, msc2, -1L)
    assertEquals(x.casB(msc1, null), false)
    checkState(x, 9, 9, 99, msc2, -1L)
    assertEquals(x.casB(msc2, null), true)
    checkState(x, 9, 9, 99, null, -1L)
    x.setBR(msc1)
    assertEquals(x.casN(42L, 1024L), false)
    checkState(x, 9, 9, 99, msc1, -1L)
    assertEquals(x.casN(-1L, 1024L), true)
    checkState(x, 9, 9, 99, msc1, 1024L)
    // cmpxchg:
    val wit1 = x.cmpxchgA(100, 101)
    assertEquals(wit1, 99)
    checkState(x, 9, 9, 99, msc1, 1024L)
    val wit2 = x.cmpxchgA(99, 100)
    assertEquals(wit2, 99)
    checkState(x, 9, 9, 100, msc1, 1024L)
    val wit3 = x.cmpxchgB(null, msc2)
    assert(wit3 eq msc1)
    checkState(x, 9, 9, 100, msc1, 1024L)
    val wit4 = x.cmpxchgB(msc1, msc2)
    assert(wit4 eq msc1)
    checkState(x, 9, 9, 100, msc2, 1024L)
    val wit5 = x.cmpxchgN(42L, 99L)
    assertEquals(wit5, 1024L)
    checkState(x, 9, 9, 100, msc2, 1024L)
    val wit6 = x.cmpxchgN(wit5, 99L)
    assertEquals(wit6, wit5)
    checkState(x, 9, 9, 100, msc2, 99L)
    // cmpxchg (acq):
    val wit7 = x.cmpxchgAA(99, 101)
    assertEquals(wit7, 100)
    checkState(x, 9, 9, 100, msc2, 99L)
    val wit8 = x.cmpxchgAA(wit7, 200)
    assertEquals(wit8, wit7)
    checkState(x, 9, 9, 200, msc2, 99L)
    val wit9 = x.cmpxchgBA(null, msc2)
    assert(wit9 eq msc2)
    checkState(x, 9, 9, 200, msc2, 99L)
    val wit10 = x.cmpxchgBA(msc2, msc1)
    assert(wit10 eq msc2)
    checkState(x, 9, 9, 200, msc1, 99L)
    val wit11 = x.cmpxchgNA(42L, 100L)
    assertEquals(wit11, 99L)
    checkState(x, 9, 9, 200, msc1, 99L)
    val wit12 = x.cmpxchgNA(wit11, 100L)
    assertEquals(wit12, wit11)
    checkState(x, 9, 9, 200, msc1, 100L)
    // cmpxchg (relacq):
    setState(x, 9, 9, 100, msc2, 99L)
    val wit13 = x.cmpxchgAA(99, 101)
    assertEquals(wit13, 100)
    checkState(x, 9, 9, 100, msc2, 99L)
    val wit14 = x.cmpxchgAA(wit13, 200)
    assertEquals(wit14, wit13)
    checkState(x, 9, 9, 200, msc2, 99L)
    val wit15 = x.cmpxchgBA(null, msc2)
    assert(wit15 eq msc2)
    checkState(x, 9, 9, 200, msc2, 99L)
    val wit16 = x.cmpxchgBA(msc2, msc1)
    assert(wit16 eq msc2)
    checkState(x, 9, 9, 200, msc1, 99L)
    val wit17 = x.cmpxchgNA(42L, 100L)
    assertEquals(wit17, 99L)
    checkState(x, 9, 9, 200, msc1, 99L)
    val wit18 = x.cmpxchgNA(wit17, 100L)
    assertEquals(wit18, wit17)
    checkState(x, 9, 9, 200, msc1, 100L)
    // faa:
    x.setNR(99L)
    assertEquals(x.faaNO(1L), 99L)
    checkState(x, 9, 9, 200, msc1, 100L)
    assertEquals(x.faaNO(42L), 100L)
    checkState(x, 9, 9, 200, msc1, 142L)
    assertEquals(x.faaNO(-143L), 142L)
    checkState(x, 9, 9, 200, msc1, -1L)
    x.setNR(99L)
    assertEquals(x.faaNA(1L), 99L)
    checkState(x, 9, 9, 200, msc1, 100L)
    assertEquals(x.faaNA(42L), 100L)
    checkState(x, 9, 9, 200, msc1, 142L)
    assertEquals(x.faaNA(-143L), 142L)
    checkState(x, 9, 9, 200, msc1, -1L)
  }

  private def checkState(x: MyClass2, expI: Int, expJ: Int, expA: Int, expB: MySubClass, expN: Long)(implicit loc: Location): Unit = {
    assertEquals(x.getIP, expI)
    assertEquals(x.getIO, expI)
    assertEquals(x.getJP, expJ)
    assertEquals(x.getJO, expJ)
    assertEquals(x.getAP, expA)
    assertEquals(x.getAO, expA)
    assertEquals(x.getBP, expB)
    assertEquals(x.getBO, expB)
    assertEquals(x.getNP, expN)
    assertEquals(x.getNO, expN)
  }

  private def setState(x: MyClass2, expI: Int, expJ: Int, expA: Int, expB: MySubClass, expN: Long)(implicit loc: Location): Unit = {
    x.setAR(expA)
    x.setBR(expB)
    x.setNR(expN)
    checkState(x, expI, expJ, expA, expB, expN)
  }
}

sealed trait MyTrait
final class MySubClass extends MyTrait

final class MyClass2(_i: Int, _j: Int, k: Int, x: MySubClass, _n: Long)
  extends MyClass1[Int, MySubClass](k, _i, _j, x, _n)

sealed abstract class MyClass1[A, B <: MyTrait](
  private[this] var a: A,
  private[this] var i: Int,
  private[this] var j: Int,
  private[this] var b: B,
  private[this] var n: Long,
) {

  @alwaysinline
  private[this] final def atomicI: AtomicIntHandle = {
    AtomicIntHandle(this, "i")
  }

  @alwaysinline
  private[this] final def atomicJ: AtomicIntHandle = {
    AtomicIntHandle(this, "j")
  }

  @alwaysinline
  private[this] final def atomicA: AtomicHandle[A] = {
    AtomicHandle(this, "a")
  }

  @alwaysinline
  private[this] final def atomicB: AtomicHandle[B] = {
    AtomicHandle(this, "b")
  }

  @alwaysinline
  private[this] final def atomicN: AtomicLongHandle = {
    AtomicLongHandle(this, "n")
  }

  final def getIP: Int =
    this.i

  final def getIO: Int =
    atomicI.getOpaque

  final def setIR(nv: Int): Unit =
    atomicI.setRelease(nv)

  final def getJP: Int =
    this.j

  final def getJO: Int =
    atomicJ.getOpaque

  final def setJR(nv: Int): Unit =
    atomicJ.setRelease(nv)

  final def getAP: A =
    this.a

  final def getAO: A =
    atomicA.getOpaque

  final def setAR(nv: A): Unit =
    atomicA.setRelease(nv)

  final def casA(ov: A, nv: A): Boolean =
    atomicA.compareAndSet(ov, nv)

  final def cmpxchgA(ov: A, nv: A): A =
    atomicA.compareAndExchange(ov, nv)

  final def cmpxchgAA(ov: A, nv: A): A =
    atomicA.compareAndExchangeAcquire(ov, nv)

  final def cmpxchgARA(ov: A, nv: A): A =
    atomicA.compareAndExchangeRelAcq(ov, nv)

  final def getBP: B =
    this.b

  final def getBO: B =
    atomicB.getOpaque

  final def setBR(nv: B): Unit =
    atomicB.setRelease(nv)

  final def casB(ov: B, nv: B): Boolean =
    atomicB.compareAndSet(ov, nv)

  final def cmpxchgB(ov: B, nv: B): B =
    atomicB.compareAndExchange(ov, nv)

  final def cmpxchgBA(ov: B, nv: B): B =
    atomicB.compareAndExchangeAcquire(ov, nv)

  final def cmpxchgBRA(ov: B, nv: B): B =
    atomicB.compareAndExchangeRelAcq(ov, nv)

  final def getNP: Long =
    this.n

  final def getNO: Long =
    atomicN.getOpaque

  final def setNR(nv: Long): Unit =
    atomicN.setRelease(nv)

  final def casN(ov: Long, nv: Long): Boolean =
    atomicN.compareAndSet(ov, nv)

  final def cmpxchgN(ov: Long, nv: Long): Long =
    atomicN.compareAndExchange(ov, nv)

  final def cmpxchgNA(ov: Long, nv: Long): Long =
    atomicN.compareAndExchangeAcquire(ov, nv)

  final def cmpxchgNRA(ov: Long, nv: Long): Long =
    atomicN.compareAndExchangeRelAcq(ov, nv)

  final def faaNA(delta: Long): Long =
    atomicN.getAndAddAcquire(delta)

  final def faaNO(delta: Long): Long =
    atomicN.getAndAddOpaque(delta)
}
