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

import scala.concurrent.duration._

import cats.syntax.all._
import cats.effect.kernel.{ Async, Deferred }

import KotlinUtils.{ KtCrt, runSync }

final class KotlinUtilsSpec extends munit.FunSuite {

  private[this] implicit val K: Async[KtCrt] =
    KotlinUtils.ceAsyncForKotlinCoroutine

  private[this] final def callWhenNonNull[A](res: A, getCb: => Function1[Either[Throwable, A], Unit]): Thread = {
    val t = new Thread(() => {
      @tailrec
      def go(): Unit = {
        val cb = getCb
        cb match {
          case null =>
            Thread.onSpinWait()
            go()
          case cb =>
            cb(Right(res))
        }
      }
      go()
    })
    t.start()
    t
  }

  test("pure") {
    assertEquals(runSync(K.pure(42)), 42)
  }

  test("raiseError") {
    val ex = new RuntimeException
    try {
      runSync(K.raiseError[Unit](ex))
      fail("should've thrown an exception")
    } catch {
      case e: RuntimeException if (e eq ex) =>
        () // ok
      case x: Throwable =>
        fail(s"unexpected exception: ${x}")
    }
  }

  test("delay") {
    var flag = false
    val crt = K.delay { flag = true; 42 }
    assert(!flag)
    assertEquals(runSync(crt), 42)
    assert(flag)
  }

  test("flatMap successful") {
    var n = 0
    var flag = false
    val crt = K.flatMap(K.pure(42)) { i =>
      K.flatMap(K.delay { n = i }) { _ =>
        K.delay { flag = true; "foo" }
      }
    }
    assertEquals(n, 0)
    assert(!flag)
    assertEquals(runSync(crt), "foo")
    assertEquals(n, 42)
    assert(flag)
  }

  test("map successful") {
    var str: String = null
    val crt = K.map(K.pure("foo")) { s =>
      str = s
      42
    }
    assertEquals(runSync(crt), 42)
    assertEquals(str, "foo")
  }

  test("flatMap failed") {
    val ex = new RuntimeException
    val crt = K.flatMap(K.delay[Unit] { throw ex }) { _ =>
      K.pure(42)
    }
    try {
      runSync(crt)
      fail("should've thrown an exception")
    } catch {
      case e: RuntimeException if (e eq ex) =>
        () // ok
      case x: Throwable =>
        fail(s"unexpected exception: ${x}")
    }
  }

  test("async") {
    @volatile var cbHolder: Function1[Either[Throwable, String], Unit] = null
    val t = callWhenNonNull("foo", cbHolder)
    val crt = K.async[String] { cb =>
      K.delay {
        cbHolder = cb
        None
      }
    }
    val res = runSync(crt)
    t.join()
    assertEquals(res, "foo")
  }

  test("async calling cb directly") {
    val crt = K.async[String] { cb =>
      K.delay {
        cb(Right("bar"))
        None
      }
    }
    assertEquals(runSync(crt), "bar")
  }

  test("async with finalizer") {
    var flag = false
    val crt = K.async[String] { _ =>
      K.pure(Some(K.delay { flag = true }))
    }
    val job = KotlinUtils.fork(crt)
    Thread.sleep(100L)
    job.cancel(null)
    assert(flag)
  }

  test("flatMap async") {
    var n: Int = 0
    @volatile var cbHolder: Either[Throwable, String] => Unit = null
    val t = callWhenNonNull("fa", cbHolder)
    val crt: KtCrt[String] = K.pure(42).flatMap { i =>
      K.async_[String] { cb =>
        cbHolder = cb
        n = i
      }.map { _ + "a" }
    }
    assertEquals(runSync(crt), "faa")
    t.join()
    assertEquals(n, 42)
  }

  test("ref") {
    val crt = K.ref("foo").flatMap { ref =>
      ref.set("bar") *> ref.get
    }
    assertEquals(runSync(crt), "bar")
  }

  test("deferred") {
    @volatile var deferred: Deferred[KtCrt, String] = null
    val t = new Thread(() => {
      @tailrec
      def go(): Unit = {
        deferred match {
          case null =>
            Thread.sleep(10L)
            go()
          case d =>
            runSync(d.complete("foo").void)
        }
      }
      go()
    })
    t.start()
    val crt = for {
      d <- K.deferred[String]
      v1 <- d.tryGet
      _ <- K.delay { assertEquals(v1, None) }
      _ <- K.delay { deferred = d }
      v2 <- d.get
    } yield v2
    val res = runSync(crt)
    t.join()
    assertEquals(res, "foo")
  }

  test("cede") {
    assertEquals(runSync(K.cede), ())
    assertEquals(runSync(K.cede.as(42)), 42)
  }

  test("cede flatMap") {
    val crt = for {
      v <- K.pure(20)
      _ <- K.cede
      vv <- K.async_[Int] { cb => cb(Right(22)) }
    } yield v + vv
    assertEquals(runSync(crt), 42)
  }

  test("sleep") {
    assertEquals(runSync(K.sleep(10.millis).as("foo")), "foo")
  }
}
