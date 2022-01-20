/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger, AtomicBoolean }

import scala.concurrent.duration._

import cats.effect.IO

final class RxnSpecJvm_SpinLockMCAS_IO
  extends BaseSpecIO
  with SpecSpinLockMCAS
  with RxnSpecJvm[IO]

final class RxnSpecJvm_SpinLockMCAS_ZIO
  extends BaseSpecZIO
  with SpecSpinLockMCAS
  with RxnSpecJvm[zio.Task]

final class RxnSpecJvm_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with RxnSpecJvm[IO]

final class RxnSpecJvm_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with RxnSpecJvm[zio.Task]

trait RxnSpecJvm[F[_]] extends RxnSpec[F] { this: KCASImplSpec =>

  test("Thread interruption in infinite retry") {
    val never = Rxn.unsafe.retry[Any, Unit]
    @volatile var exception = Option.empty[Throwable]
    F.blocking {
      val cdl = new CountDownLatch(1)
      val t = new Thread(() => {
        cdl.countDown()
        never.unsafeRun(this.kcasImpl)
      })
      t.setUncaughtExceptionHandler((_, ex) => {
        if (!ex.isInstanceOf[InterruptedException]) {
          // ignore interrupt, otherwise fail the test
          exception = Some(ex)
        }
      })
      t.start()
      assert(t.isAlive())
      cdl.await()
      Thread.sleep(1000L)
      assert(t.isAlive())
      t.interrupt()
      var c = 0
      while (t.isAlive() && (c < 2000)) {
        c += 1
        Thread.sleep(1L)
      }
      assert(!t.isAlive())
      exception.foreach(throw _)
    }
  }

  test("Thread interruption with alternatives") {
    val ref = Ref.unsafe("a")
    val never: Axn[Unit] = (1 to 1000).foldLeft(ref.unsafeCas("foo", "bar")) { (acc, num) =>
      acc + ref.unsafeCas(ov = num.toString(), nv = "foo")
    }
    @volatile var exception = Option.empty[Throwable]
    F.blocking {
      val cdl = new CountDownLatch(1)
      val t = new Thread(() => {
        cdl.countDown()
        never.unsafeRun(this.kcasImpl)
      })
      t.setUncaughtExceptionHandler((_, ex) => {
        if (!ex.isInstanceOf[InterruptedException]) {
          // ignore interrupt, otherwise fail the test
          exception = Some(ex)
        }
      })
      t.start()
      assert(t.isAlive())
      cdl.await()
      Thread.sleep(1000L)
      assert(t.isAlive())
      t.interrupt()
      var c = 0
      while (t.isAlive() && (c < 2000)) {
        c += 1
        Thread.sleep(1L)
      }
      assert(!t.isAlive())
      exception.foreach(throw _)
    }
  }

  test("Autoboxing (JVM)") {
    // Integers between (typically) -128 and 127 are
    // cached. Due to autoboxing, other integers may
    // seem to change their "identity".
    val n = 9999999
    for {
      _ <- F.delay {
        // check the assumption above:
        assertIntIsNotCached(n)
      }
      ref <- Ref[Int](n).run[F]
      // `update` works fine:
      _ <- ref.update(_ + 1).run[F]
      _ <- assertResultF(ref.get.run[F], n + 1)
      // `unsafeInvisibleRead` then `unsafeCas` doesn't:
      unsafeRxn = ref.unsafeInvisibleRead.flatMap { v =>
        Rxn.pure(42).flatMap { _ =>
          ref.unsafeCas(ov = v, nv = v + 1)
        }
      }
      fib <- F.interruptible {
        unsafeRxn.unsafePerform((), this.kcasImpl)
      }.start
      _ <- F.sleep(0.5.second)
      _ <- fib.cancel
      _ <- assertResultF(ref.get.run[F], n + 1) // no change
      // but it *seems* to work with small numbers:
      _ <- ref.getAndSet[F](42)
      _ <- unsafeRxn.run[F]
      _ <- assertResultF(ref.get.run[F], 43)
    } yield ()
  }

  test("Zombie") {
    for {
      ref1 <- Ref("a").run[F]
      ref2 <- Ref("b").run[F]
      ref <- F.delay(new AtomicReference[(String, String)])
      writerDone <- F.delay(new AtomicBoolean(false))
      unsafeLog <- F.delay(new AtomicReference[List[(String, String)]](Nil))
      writer = (ref1.update { v1 => v1 + "a" } *> ref2.update(_ + "b")).run[F]
      reader = ref1.get.flatMapF { v1 =>
        // we already read `ref1`; now we start
        // `writer`, and hard block until it's
        // committed
        if ((ref.get() eq null) && (!writerDone.get())) {
          this.absolutelyUnsafeRunSync(
            writer.start.flatMap(_.joinWithNever) >> F.delay {
              writerDone.set(true)
            }
          ) : Unit
        }
        // read value unsafely:
        ref2.unsafeInvisibleRead.flatMap { unsafeValue =>
          unsafeLog.accumulateAndGet(List((v1, unsafeValue)), (l1, l2) => l1 ++ l2)
          // then we continue with reading (the now
          // changed) `ref2`:
          ref2.get.map { v2 =>
            val tup = (v1, v2)
            if (v2.length > v1.length) {
              ref.compareAndSet(null, tup)
            } else {
              ref.compareAndSet(null, ("OK", "OK"))
            }
            tup
          }
        }
      }
      rRes <- reader.run[F]
      _ <- assertEqualsF(rRes, ("aa", "bb"))
      _ <- assertResultF(
        F.delay(ref.get()),
        ("OK", "OK"),
      )
      _ <- assertResultF(
        F.delay(unsafeLog.get()),
        List(("a", "bb"), ("aa", "bb")),
      )
    } yield ()
  }

  test("Zombie infinite loop") {
    val c = new AtomicInteger(0)
    @tailrec
    def infiniteLoop(@unused n: Int = 0): Int = {
      if (Thread.interrupted()) {
        throw new InterruptedException
      } else {
        infiniteLoop(c.incrementAndGet())
      }
    }
    val tsk = for {
      ref1 <- Ref("a").run[F]
      ref2 <- Ref("b").run[F]
      writer = (ref1.update(_ + "a") *> ref2.update(_ + "b")).run[F]
      reader = F.interruptible {
        Rxn.consistentRead(ref1, ref2).map { v12 =>
          if (v12._1.length != v12._2.length) {
            infiniteLoop().toString() -> "x"
          } else {
            v12
          }
        }.unsafeRun(this.kcasImpl)
      }
      _ <- F.both(F.cede *> writer, F.cede *> reader)
    } yield ()
    // we hard block here, because we don't want the munit timeout:
    this.absolutelyUnsafeRunSync(
      F.replicateA(1024, tsk).void.timeoutTo(
        1.minute,
        this.failF("infinite loop")
      )
    )
  }
}
