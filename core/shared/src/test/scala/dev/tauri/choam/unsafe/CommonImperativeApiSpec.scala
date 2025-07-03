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

import core.Ref

object CommonImperativeApiSpec {

  final class MyException(val ref: Ref[Int]) extends Exception
}

trait CommonImperativeApiSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with CommonImperativeApiSpecPlatform[F] { this: McasImplSpec =>

  import CommonImperativeApiSpec.MyException

  def runBlock[A](block: InRxn => A): F[A]

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

    for {
      r <- runBlock { implicit txn =>
        myTxn()
      }
      (v1, v2, ref) = r
      _ <- assertEqualsF(v1, 42)
      _ <- assertEqualsF(v2, 99)
      v3 <- runBlock(readRef(ref)(using _))
      _ <- assertEqualsF(v3, 99)
    } yield ()
  }

  test("updateRef") {
    for {
      r <- runBlock(newRef("foo")(using _))
      _ <- runBlock { implicit ir =>
        updateRef(r)(_ + "bar")
      }
      _ <- assertResultF(runBlock(readRef(r)(using _)), "foobar")
    } yield ()
  }

  test("getAndSetRef") {
    for {
      r <- runBlock(newRef("foo")(using _))
      res <- runBlock { implicit ir =>
        getAndSetRef(r, "bar")
      }
      _ <- assertEqualsF(res, "foo")
      _ <- assertResultF(runBlock(readRef(r)(using _)), "bar")
    } yield ()
  }

  test("tentativeRead") {
    for {
      r1 <- runBlock(newRef(0)(using _))
      r2 <- runBlock(newRef(0)(using _))
      _ <- runBlock { implicit ir =>
        assertEquals(tentativeRead(r1), 0)
        updateRef(r2)(_ + 1)
        assertEquals(tentativeRead(r1), 0)
        updateRef(r1)(_ + 1)
        assertEquals(tentativeRead(r1), 1)
      }
    } yield ()
  }

  test("ticketRead") {
    for {
      r1 <- runBlock(newRef(0)(using _))
      r2 <- runBlock(newRef(0)(using _))
      _ <- runBlock { implicit ir =>
        val ticket = ticketRead(r1)
        assertEquals(ticket.value, 0)
        updateRef(r2)(_ + 1)
        ticket.value = 42
        assertEquals(r1.value, 42)
      }
      _ <- assertResultF(runBlock(readRef(r1)(using _)), 42)
      _ <- assertResultF(runBlock(readRef(r2)(using _)), 1)
    } yield ()
  }

  test("RefSyntax") {
    def useRef(ref: Ref[Int])(implicit ir: InRxn): Unit = {
      val v1 = ref.value
      assertEquals(v1, 42)
      ref.value = 99
      assertEquals(ref.value, 99)
    }
    for {
      ref <- runBlock(newRef(42)(using _))
      _ <- runBlock { implicit ir =>
        useRef(ref)
      }
      _ <- runBlock { implicit ir =>
        assertEquals(ref.value, 99)
      }
    } yield ()
  }

  test("Ref.Array") {
    for {
      arr1 <- runBlock(newRefArray[String](16, "")(using _))
      _ = (arr1: Ref.Array[String])
      r1 <- runBlock { implicit ir =>
        arr1.unsafeGet(3).value = "foo"
        arr1.unsafeGet(3).value
      }
      _ <- assertEqualsF(r1, "foo")
      rr <-  runBlock { implicit ir =>
        val arr2 = newRefArray[String](16, "x")
        assertEquals(arr2.unsafeGet(4).value, "x")
        assertEquals(arr1.unsafeGet(4).value, "")
        assertEquals(arr1.unsafeGet(3).value, "foo")
        arr2.unsafeGet(5).value = "xyz"
        (arr2.unsafeGet(5).value, arr2)
      }
      (r2, arr2) = rr
      _ <- assertEqualsF(r2, "xyz")
      r3 <- runBlock { implicit ir =>
        arr2.unsafeGet(5).value
      }
      _ <- assertEqualsF(r3, "xyz")
    } yield ()
  }

  test("Exception passthrough") {
    testExcPass()
  }

  @nowarn("cat=w-flag-dead-code")
  private def testExcPass(): F[Unit] = for {
    r <- (runBlock { implicit ir =>
      val ref = newRef(42)
      ref.value = 99
      throw new MyException(ref)
      ref.value
    }).attempt
    _ <- r match {
      case Right(i) =>
        failF[Unit](s"Expected an exception, got: $i")
      case Left(ex: MyException) =>
        runBlock(readRef(ex.ref)(using _)).flatMap { v =>
          assertEqualsF(v, 42) // the write must be rollbacked
        }
      case Left(ex) =>
        failF(s"Unexpected exception: $ex")
    }
  } yield ()

  test("Forced retries (1)") {
    for {
      ctr <- F.delay(new AtomicInteger)
      ref <- runBlock(newRef(42)(using _))
      res <- runBlock { implicit ir =>
        updateRef(ref)(_ + 1)
        if (ctr.incrementAndGet() < 5) {
          alwaysRetry()
        }
        ref.value
      }
      _ <- assertEqualsF(res, 43)
      _ <- assertResultF(runBlock(ref.value(using _)), 43)
      _ <- assertResultF(F.delay(ctr.get()), 5)
    } yield ()
  }

  test("Forced retries (2)") {
    for {
      ctr <- F.delay(new AtomicInteger)
      ref <- runBlock(newRef(42)(using _))
      tsk = runBlock { implicit ir =>
        updateRef(ref)(_ + 1)
        if (ctr.incrementAndGet() < 5) {
          alwaysRetry()
        }
        ref.value
      }
      _ <- assertResultF(tsk, 43)
      _ <- assertResultF(runBlock(ref.value(using _)), 43)
      _ <- assertResultF(F.delay(ctr.get()), 5)
      _ <- F.delay(ctr.set(0))
      _ <- assertResultF(tsk, 44)
      _ <- assertResultF(runBlock(ref.value(using _)), 44)
      _ <- assertResultF(F.delay(ctr.get()), 5)
    } yield ()
  }

  test("null result") {
    for {
      flag <- F.delay(new AtomicBoolean(true))
      ref <- runBlock(newRef(42)(using _))
      res <- runBlock[String] { implicit ir =>
        ref.value = ref.value + 1
        if (flag.getAndSet(false)) {
          alwaysRetry()
        }
        null
      }
      _ <- assertEqualsF(res, null)
      _ <- assertResultF(runBlock(ref.value(using _)), 43)
      _ <- assertResultF(F.delay(flag.get()), false)
    } yield ()
  }
}
