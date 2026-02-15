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

import java.util.concurrent.atomic.{ AtomicInteger, AtomicBoolean }

import core.{ Rxn, Ref }

object CommonImperativeApiSpec {

  final class MyException(val ref: Ref[Int]) extends Exception
}

trait CommonImperativeApiSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with CommonImperativeApiSpecPlatform[F] { this: McasImplSpec =>

  import CommonImperativeApiSpec.MyException

  def runBlock[A](block: InRxn => A): F[A]

  def runRoBlock[A](block: InRoRxn => A): F[A]

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

  test("tentativeReadArray") {
    for {
      arr <- runBlock(newRefArray(3, initial = 0)(using _))
      _ <- runBlock { implicit ir =>
        assertEquals(tentativeReadArray(arr, 1), 0)
        updateRefArray(arr, 2)(_ + 1)
        assertEquals(tentativeReadArray(arr, 1), 0)
        updateRefArray(arr, 1)(_ + 1)
        assertEquals(tentativeReadArray(arr, 1), 1)
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

  test("ticketReadArray") {
    for {
      arr <- runBlock(newRefArray(3, initial = 0)(using _))
      _ <- runBlock { implicit ir =>
        val ticket = ticketReadArray(arr, 1)
        assertEquals(ticket.value, 0)
        updateRefArray(arr, 2)(_ + 1)
        ticket.value = 42
        assertEquals(arr(1), 42)
      }
      _ <- assertResultF(runBlock(readRefArray(arr, 0)(using _)), 0)
      _ <- assertResultF(runBlock(readRefArray(arr, 1)(using _)), 42)
      _ <- assertResultF(runBlock(readRefArray(arr, 2)(using _)), 1)
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

  test("RefArraySyntax") {
    def useArr(arr: Ref.Array[Int])(implicit ir: InRxn): Unit = {
      val v1 = arr(0)
      assertEquals(v1, 0)
      arr(0) = 42
      assertEquals(arr(0), 42)
      assertEquals(arr(1), 0)
      assert(Either.catchNonFatal { arr(2) }.isLeft)
      assert(Either.catchNonFatal { arr(-1) }.isLeft)
    }
    for {
      arr <- runBlock(newRefArray(2, 0)(using _))
      _ <- runBlock { implicit ir =>
        useArr(arr)
      }
      _ <- runBlock { implicit ir =>
        assertEquals(arr(0), 42)
      }
    } yield ()
  }

  test("Ref.Array") {
    for {
      arr1 <- runBlock(newRefArray[String](16, "")(using _))
      _ = (arr1: Ref.Array[String])
      r1 <- runBlock { implicit ir =>
        arr1(3) = "foo"
        arr1(3)
      }
      _ <- assertEqualsF(r1, "foo")
      rr <-  runBlock { implicit ir =>
        val arr2 = newRefArray[String](16, "x")
        assertEquals(arr2(4), "x")
        assertEquals(arr1(4), "")
        assertEquals(arr1(3), "foo")
        arr2(5) = "xyz"
        (arr2(5), arr2)
      }
      (r2, arr2) = rr
      _ <- assertEqualsF(r2, "xyz")
      r3 <- runBlock { implicit ir =>
        arr2(5)
      }
      _ <- assertEqualsF(r3, "xyz")
      _ <- runBlock { implicit ir =>
        updateRefArray(arr2, 5)(_ + "9")
      }
      r4 <- runBlock(arr2(5)(using _))
      _ <- assertEqualsF(r4, "xyz9")
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

  test("panic") {
    val ex = new MyException(null)
    val fa: F[Int] = runBlock { implicit ir =>
      panic(ex) : Int
    }
    assertResultF(fa.attempt, Left(ex))
  }

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

  test("Create with Rxn, use imperatively") {
    Ref(42).run[F].flatMap { ref =>
      runBlock { implicit ir =>
        assertEquals(ref.value, 42)
        ref.value = 99
        assertEquals(ref.value, 99)
      } *> assertResultF(ref.get.run[F], 99)
    }
  }

  test("Create imperatively, use with Rxn") {
    for {
      ref <- runBlock(newRef(42)(using _))
      r <- ref.getAndUpdate(_ + 1).run[F]
      _ <- assertEqualsF(r, 42)
      _ <- assertResultF(ref.get.run[F], 43)
      _ <- assertResultF(runBlock(readRef(ref)(using _)), 43)
    } yield ()
  }

  test("Post-commit actions in imperative API") {
    for {
      ref1 <- Ref(0).run[F]
      ref2 <- Ref(0).run[F]
      ref3 <- Ref(0).run[F]
      res <- runBlock { implicit ir =>
        updateRef(ref1)(_ + 1)
        addPostCommit(Rxn.unsafe.embedUnsafe { implicit ir =>
          writeRef(ref3, ref1.value + ref2.value)
        })
        updateRef(ref2)(_ + 1)
        42
      }
      _ <- assertEqualsF(res, 42)
      _ <- assertResultF(ref1.get.run, 1)
      _ <- assertResultF(ref2.get.run, 1)
      _ <- assertResultF(ref3.get.run, 2)
    } yield ()
  }

  test("Read-only") {
    for {
      ref <- runBlock(newRef(1)(using _))
      arr <- runBlock(newRefArray(2, "")(using _))
      _ <- runBlock(writeRef(ref, 42)(using _))
      _ <- runBlock(writeRefArray(arr, 0, "foo")(using _))
      _ <- assertResultF(runRoBlock { implicit ir =>
        ref.value
      }, 42)
      _ <- assertResultF(runRoBlock(readRef(ref)(using _)), 42)
      _ <- assertResultF(runRoBlock(readRefArray(arr, 0)(using _)), "foo")
      _ <- runRoBlock { implicit ir =>
        assertEquals(arr(0), "foo")
        assertEquals(arr(1), "")
      }
      _ <- assertResultF(runRoBlock(tentativeRead(ref)(using _)), 42)
      _ <- assertResultF(runRoBlock(tentativeReadArray(arr, 0)(using _)), "foo")
      v1 <- runRoBlock { implicit ir =>
        val t = ticketRead(ref)
        t.value
      }
      _ <- assertEqualsF(v1, 42)
      v1a <- runRoBlock { implicit ir =>
        val t = ticketReadArray(arr, 0)
        t.value
      }
      _ <- assertEqualsF(v1a, "foo")
      v2a <- runRoBlock { implicit ir =>
        val t = ticketReadArray(arr, 0)
        t.validate()
        t.value
      }
      _ <- assertEqualsF(v2a, "foo")
    } yield  ()
  }

  test("RxnLocal in imperative API") {
    for {
      loc <- runBlock { implicit ir =>
        val loc = newLocal("foo")
        val loc2 = newLocal(42)
        assertEquals(loc.value, "foo")
        assertEquals(loc2.value, 42)
        loc.value = "bar"
        assertEquals(loc.value, "bar")
        assertEquals(loc2.value, 42)
        loc2.value = 99
        assertEquals(loc.value, "bar")
        assertEquals(loc2.value, 99)
        loc
      }
      _ <- assertResultF(runBlock { implicit ir => loc.value }, "foo")
    } yield ()
  }

  test("RxnLocal.Array in imperative API") {
    for {
      arr <- runBlock { implicit ir =>
        val arr = newLocalArray(3, "foo")
        val arr2 = newLocalArray(3, 42)
        assertEquals(arr(0), "foo")
        assertEquals(arr2(0), 42)
        arr(0) = "bar"
        assertEquals(arr(0), "bar")
        assertEquals(arr(1), "foo")
        assertEquals(arr(2), "foo")
        assertEquals(arr2(0), 42)
        arr2(0) = 99
        assertEquals(arr(0), "bar")
        assertEquals(arr(1), "foo")
        assertEquals(arr(2), "foo")
        assertEquals(arr2(0), 99)
        assertEquals(arr2(1), 42)
        assertEquals(arr2(2), 42)
        assert(Either.catchOnly[IndexOutOfBoundsException] { arr(3) }.isLeft)
        assert(Either.catchOnly[IndexOutOfBoundsException] { arr(-1) }.isLeft)
        arr
      }
      _ <- runBlock { implicit ir =>
        assertEquals(arr(0), "foo")
        assertEquals(arr(1), "foo")
        assertEquals(arr(2), "foo")
        // TODO: assert(Either.catchOnly[IndexOutOfBoundsException] { arr(3) }.isLeft)
      }
    } yield ()
  }
}
