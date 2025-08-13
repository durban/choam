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

import scala.scalanative.annotation.alwaysinline

final class AtomicHandleSpec extends munit.FunSuite {

  test("Basics") {
    val msc1 = new MySubClass
    val msc2 = new MySubClass
    val x = new MyClass2(3, msc1)
    checkState(x, 3, msc1)
    x.setAR(4)
    checkState(x, 4, msc1)
    x.setBR(msc2)
    checkState(x, 4, msc2)
    assertEquals(x.casA(42, 99), false)
    checkState(x, 4, msc2)
    assertEquals(x.casA(4, 99), true)
    checkState(x, 99, msc2)
    assertEquals(x.casB(msc1, null), false)
    checkState(x, 99, msc2)
    assertEquals(x.casB(msc2, null), true)
    checkState(x, 99, null)
  }

  private def checkState(x: MyClass2, expA: Int, expB: MySubClass): Unit = {
    assertEquals(x.getAP, expA)
    assertEquals(x.getAO, expA)
    assertEquals(x.getBP, expB)
    assertEquals(x.getBO, expB)
  }
}

sealed trait MyTrait
final class MySubClass extends MyTrait

final class MyClass2(k: Int, x: MySubClass) extends MyClass1[Int, MySubClass](k, x)

sealed abstract class MyClass1[A, B <: MyTrait](
  private[this] var a: A,
  private[this] var b: B,
) {

  @alwaysinline
  private[this] final def atomicA: AtomicHandle[A] = {
    AtomicHandle(this, "a")
  }

  @alwaysinline
  private[this] final def atomicB: AtomicHandle[B] = {
    AtomicHandle(this, "b")
  }

  final def getAP: A =
    this.a

  final def getAO: A =
    atomicA.getOpaque

  final def setAR(nv: A): Unit =
    atomicA.setRelease(nv)

  final def casA(ov: A, nv: A): Boolean =
    atomicA.compareAndSet(ov, nv)

  final def getBP: B =
    this.b

  final def getBO: B =
    atomicB.getOpaque

  final def setBR(nv: B): Unit =
    atomicB.setRelease(nv)

  final def casB(ov: B, nv: B): Boolean =
    atomicB.compareAndSet(ov, nv)
}
