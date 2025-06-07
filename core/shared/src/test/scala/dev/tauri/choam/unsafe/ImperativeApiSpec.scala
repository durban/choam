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

import java.util.concurrent.atomic.{ AtomicInteger, AtomicBoolean }

import munit.FunSuite

import core.Ref

final class ImperativeApiSpec extends FunSuite with MUnitUtils {

  private[this] val _rt: ChoamRuntime =
    ChoamRuntime.unsafeBlocking()

  private[this] val api: UnsafeApi =
    UnsafeApi(_rt)

  final override def afterAll(): Unit = {
    _rt.unsafeCloseBlocking()
    super.afterAll()
  }

  import api._

  test("Hello, World!") {

    def write(ref: Ref[Int])(implicit txn: InRxn): Unit = {
      writeRef(ref, 99)
    }

    def read(ref: Ref[Int])(implicit txn: InRxn): Int = {
      readRef(ref)
    }

    def myTxn()(implicit txn: InRxn): (Int, Int, Ref[Int]) = {
      val ref = newRef(42)
      val v1 = read(ref)
      write(ref)
      val v2 = read(ref)
      (v1, v2, ref)
    }

    val (v1, v2, ref) = atomically { implicit txn =>
      myTxn()
    }

    assertEquals(v1, 42)
    assertEquals(v2, 99)
    val v3 = atomically(readRef(ref)(_))
    assertEquals(v3, 99)
  }

  test("updateRef") {
    val r = atomically(newRef("foo")(_))
    atomically { implicit ir =>
      updateRef(r)(_ + "bar")
    }
    assertEquals(atomically(readRef(r)(_)), "foobar")
  }

  test("tentativeRead") {
    val r1 = atomically(newRef(0)(_))
    val r2 = atomically(newRef(0)(_))
    atomically { implicit ir =>
      assertEquals(tentativeRead(r1), 0)
      updateRef(r2)(_ + 1)
      assertEquals(tentativeRead(r1), 0)
      updateRef(r1)(_ + 1)
      assertEquals(tentativeRead(r1), 1)
    }
  }

  test("ticketRead") {
    val r1 = atomically(newRef(0)(_))
    val r2 = atomically(newRef(0)(_))
    atomically { implicit ir =>
      val ticket = ticketRead(r1)
      assertEquals(ticket.value, 0)
      updateRef(r2)(_ + 1)
      ticket.value = 42
      assertEquals(r1.value, 42)
    }
    assertEquals(atomically(readRef(r1)(_)), 42)
    assertEquals(atomically(readRef(r2)(_)), 1)
  }

  test("RefSyntax") {
    def useRef(ref: Ref[Int])(implicit ir: InRxn): Unit = {
      val v1 = ref.value
      assertEquals(v1, 42)
      ref.value = 99
      assertEquals(ref.value, 99)
    }
    val ref = atomically(newRef(42)(_))
    atomically { implicit ir =>
      useRef(ref)
    }
    atomically { implicit ir =>
      assertEquals(ref.value, 99)
    }
  }

  test("Ref.Array") {
    val arr1: Ref.Array[String] = atomically(newRefArray[String](16, "")(_))
    val r1 = atomically { implicit ir =>
      arr1.unsafeGet(3).value = "foo"
      arr1.unsafeGet(3).value
    }
    assertEquals(r1, "foo")
    val (r2, arr2) = atomically { implicit ir =>
      val arr2 = newRefArray[String](16, "x")
      assertEquals(arr2.unsafeGet(4).value, "x")
      assertEquals(arr1.unsafeGet(4).value, "")
      assertEquals(arr1.unsafeGet(3).value, "foo")
      arr2.unsafeGet(5).value = "xyz"
      (arr2.unsafeGet(5).value, arr2)
    }
    assertEquals(r2, "xyz")
    val r3 = atomically { implicit ir =>
      arr2.unsafeGet(5).value
    }
    assertEquals(r3, "xyz")
  }

  private final class MyException(val ref: Ref[Int]) extends Exception

  test("Exception passthrough") {
    testExcPass()
  }

  @nowarn("cat=w-flag-dead-code")
  private def testExcPass(): Unit = {
    try {
      val i: Int = atomically { implicit ir =>
        val ref = newRef(42)
        ref.value = 99
        throw new MyException(ref)
        ref.value
      }
      fail(s"Expected an exception, got: $i")
    } catch {
      case ex: MyException =>
        val v = atomically(readRef(ex.ref)(_))
        assertEquals(v, 42) // the write must be rollbacked
    }
  }

  test("Forced retries") {
    val ctr = new AtomicInteger
    val ref = atomically(newRef(42)(_))
    val res = atomically { implicit ir =>
      updateRef(ref)(_ + 1)
      if (ctr.incrementAndGet() < 5) {
        alwaysRetry()
      }
      ref.value
    }
    assertEquals(res, 43)
    assertEquals(atomically(ref.value(_)), 43)
    assertEquals(ctr.get(), 5)
  }

  test("null result") {
    val flag = new AtomicBoolean(true)
    val ref = atomically(newRef(42)(_))
    val res = atomically[String] { implicit ir =>
      ref.value = ref.value + 1
      if (flag.getAndSet(false)) {
        alwaysRetry()
      }
      null
    }
    assertEquals(res, null)
    assertEquals(atomically(ref.value(_)), 43)
    assert(!flag.get())
  }
}
