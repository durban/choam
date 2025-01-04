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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger, AtomicBoolean }

import scala.concurrent.duration._

import cats.effect.IO

final class RxnSpecJvm_SpinLockMcas_IO
  extends BaseSpecIO
  with SpecSpinLockMcas
  with RxnSpecJvm[IO]

final class RxnSpecJvm_SpinLockMcas_ZIO
  extends BaseSpecZIO
  with SpecSpinLockMcas
  with RxnSpecJvm[zio.Task]

final class RxnSpecJvm_Emcas_IO
  extends BaseSpecIO
  with RxnSpecJvm_Emcas[IO]

final class RxnSpecJvm_Emcas_ZIO
  extends BaseSpecZIO
  with RxnSpecJvm_Emcas[zio.Task]

trait RxnSpecJvm_Emcas[F[_]] extends RxnSpecJvm[F] with SpecEmcas {

  test("Commit retry due to version") {
    // There used to be a mechanism to retry
    // only the MCAS (and not the whole Rxn)
    // if the MCAS failed due to the global
    // version CAS failing. But since we don't
    // need this mechanism for the "proper"
    // MCAS impl (`Emcas`), it has been removed.
    // `Emcas` achieves the same result in a
    // different way (it doesn't have a global
    // verison CAS), so it is the only MCAS
    // that can still pass this test.
    for {
      ref1 <- Ref("a").run[F]
      ref2 <- Ref("x").run[F]
      ctr <- F.delay(new AtomicInteger(0))
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      rxn1 = ref1.get.flatMapF { a =>
        ctr.incrementAndGet()
        latch1.countDown()
        // another commit changes the global version here
        latch2.await()
        ref1.set.provide(a + "a")
      }
      tsk1 = rxn1.run[F]
      rxn2 = ref2.set.provide("y")
      tsk2 = F.delay(latch1.await()) *> rxn2.run[F] *> F.delay(latch2.countDown())
      _ <- F.both(tsk1, tsk2)
      _ <- assertResultF(ref1.get.run[F], "aa")
      _ <- assertResultF(ref2.get.run[F], "y")
      // only the actual MCAS should've been retried, not the whole Rxn:
      _ <- assertResultF(F.delay(ctr.get()), 1)
    } yield ()
  }
}

trait RxnSpecJvm[F[_]] extends RxnSpec[F] { this: McasImplSpec =>

  test("Thread interruption in infinite retry") {
    val never = Rxn.unsafe.retry[Any, Unit]
    @volatile var exception = Option.empty[Throwable]
    F.blocking {
      val cdl = new CountDownLatch(1)
      val t = new Thread(() => {
        cdl.countDown()
        never.unsafeRun(this.mcasImpl)
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
      // `unsafeDirectRead` then `unsafeCas` doesn't:
      unsafeRxn = ref.unsafeDirectRead.flatMap { v =>
        Rxn.pure(42).flatMap { _ =>
          ref.unsafeCas(ov = v, nv = v + 1)
        }
      }
      fib <- F.interruptible {
        unsafeRxn.unsafePerform((), this.mcasImpl)
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
        ref2.unsafeDirectRead.flatMap { unsafeValue =>
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
        Ref.consistentRead(ref1, ref2).map { v12 =>
          if (v12._1.length != v12._2.length) {
            infiniteLoop().toString() -> "x"
          } else {
            v12
          }
        }.unsafeRun(this.mcasImpl)
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

  test("Read-write-read-write") {
    for {
      ref <- Ref("a").run[F]
      log <- F.delay(new AtomicReference[List[String]](Nil))
      r = ref.get.flatMapF { v0 =>
        log.accumulateAndGet(List(v0), (l1, l2) => l1 ++ l2)
        ref.update { v1 =>
          log.accumulateAndGet(List(v1), (l1, l2) => l1 ++ l2)
          v1 + "a"
        }.flatMapF { _ =>
          ref.get.flatMapF { v2 =>
            log.accumulateAndGet(List(v2), (l1, l2) => l1 ++ l2)
            Rxn.pure(v2 + "a") >>> ref.getAndSet
          }
        }
      }
      res <- r.run[F]
      _ <- assertEqualsF(res, "aa")
      l <- F.delay(log.get())
      _ <- assertEqualsF(l, List("a", "a", "aa"))
      _ <- assertResultF(ref.get.run[F], "aaa")
    } yield ()
  }

  test("unsafe.forceValidate (concurrent unrelated change)") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("x").run[F]
      ctr <- F.delay(new AtomicInteger(0))
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      _ <- r1.update { _ => "b" }.run[F]
      rxn1 = r1.get.flatMapF { v1 =>
        ctr.incrementAndGet()
        latch1.countDown()
        // concurrent unrelated change to r2
        latch2.await()
        Rxn.unsafe.forceValidate.as(v1)
      }
      tsk1 = rxn1.run[F]
      rxn2 = r2.set.provide("y")
      tsk2 = F.delay(latch1.await()) *> rxn2.run[F] *> F.delay(latch2.countDown())
      v1 <- F.both(tsk1, tsk2).map(_._1)
      _ <- assertEqualsF(v1, "b")
      _ <- assertResultF(r1.get.run[F], "b")
      _ <- assertResultF(r2.get.run[F], "y")
      _ <- assertResultF(F.delay(ctr.get()), 1)
    } yield ()
  }

  test("unsafe.forceValidate (concurrent conflicting change)") {
    for {
      r1 <- Ref("a").run[F]
      ctr <- F.delay(new AtomicInteger(0))
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      _ <- r1.update { _ => "b" }.run[F]
      rxn1 = r1.get.flatMapF { v1 =>
        ctr.incrementAndGet()
        latch1.countDown()
        // concurrent conflicting change to r1
        latch2.await()
        Rxn.unsafe.forceValidate.as(v1)
      }
      tsk1 = rxn1.run[F]
      rxn2 = r1.set.provide("c")
      tsk2 = F.delay(latch1.await()) *> rxn2.run[F] *> F.delay(latch2.countDown())
      v1 <- F.both(tsk1, tsk2).map(_._1)
      _ <- assertEqualsF(v1, "c")
      _ <- assertResultF(r1.get.run[F], "c")
      _ <- assertResultF(F.delay(ctr.get()), 2)
    } yield ()
  }

  test("read-write conflict cycle") {
    val t = for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("x").run[F]
      rxn1 = r1.getAndSet.provide("b") *> r2.get // [(r1, "a", "b"), (r2, "x", "x")]
      rxn2 = r2.getAndSet.provide("y") *> r1.get // [(r1, "a", "a"), (r2, "x", "y")]
      rss <- F.both(rxn1.run[F], rxn2.run[F])
      (rs1, rs2) = rss
      _ <- assertF(((rs1 === "x") && (rs2 === "b")) || ((rs1 === "y") && (rs2 === "a")))
    } yield ()
    t.replicateA_(10000)
  }

  test("indirect cycle") {
    val t = for {
      r0 <- Ref(0).run[F]
      r1 <- Ref("a").run[F]
      r2 <- Ref("x").run[F]
      rxn0 = r0.update(_ + 1) *> Rxn.fastRandom.nextBoolean.flatMapF {
        case true => r1.get
        case false => r2.get
      }
      rxn1 = r1.getAndSet.provide("b") *> r2.get // [(r1, "a", "b"), (r2, "x", "x")]
      rxn2 = r2.getAndSet.provide("y") *> r1.get // [(r1, "a", "a"), (r2, "x", "y")]
      rss <- F.both(rxn0.run[F], F.both(rxn1.run[F], rxn2.run[F]))
      (rs0, (rs1, rs2)) = rss
      _ <- assertF((rs0 === "a") || (rs0 === "b") || (rs0 === "x") || (rs0 === "y"))
      _ <- assertF((clue(rs1) === "x") || (rs1 === "y"))
      _ <- assertF((rs2 === "a") || (rs2 === "b"))
    } yield ()
    t.replicateA_(10000)
  }

  test("read-mostly `Rxn`s with cycle") {
    val N = 64
    val P = 16
    def readMostlyRxn(refs: List[Ref[Int]]): Axn[Int] = {
      Rxn.fastRandom.shuffleList(refs).flatMapF { sRefs =>
        sRefs.tail.take(refs.size >> 1).traverse(ref => ref.get) *> sRefs.head.getAndUpdate(_ + 1)
      }
    }
    val t = for {
      refs <- Ref(0).run[F].replicateA(N)
      tsk = readMostlyRxn(refs).run[F]
      _ <- tsk.parReplicateA_(P)(cats.effect.instances.spawn.parallelForGenSpawn)
      _ <- assertResultF(refs.traverse(_.get).map(_.sum).run[F], P)
    } yield ()
    t.replicateA_(if (this.isOpenJdk()) 10000 else 1000)
  }

  test("read-only `Rxn`s") {
    val t = for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("x").run[F]
      rxn1 = r1.get * r2.get
      rxn2 = r2.get * r1.get
      rss <- F.both(rxn1.run[F], rxn2.run[F])
      _ <- assertEqualsF(rss._1, ("a", "x"))
      _ <- assertEqualsF(rss._2, ("x", "a"))
      _ <- assertResultF((r1.get * r2.get).run[F], ("a", "x"))
    } yield ()
    t.replicateA_(10000)
  }

  test("read-only/read-write `Rxn`s") {
    val t = for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("x").run[F]
      rxn1 = r1.get <* r2.set.provide("y")
      rxn2 = r2.get * r1.get
      rss <- F.both(rxn1.run[F], rxn2.run[F])
      _ <- assertEqualsF(rss._1, "a")
      _ <- assertF((rss._2 === ("x", "a")) || (rss._2 === ("y", "a")))
      _ <- assertResultF((r1.get * r2.get).run[F], ("a", "y"))
    } yield ()
    t.replicateA_(10000)
  }

  test("read-only/write-only `Rxn`s") {
    val t = for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("x").run[F]
      rxn1 = r1.set.provide("b") *> r2.set.provide("y")
      rxn2 = r2.get * r1.get
      rss <- F.both(rxn1.run[F], rxn2.run[F])
      _ <- assertF((rss._2 === ("x", "a")) || (rss._2 === ("y", "b")))
      _ <- assertResultF((r1.get * r2.get).run[F], ("b", "y"))
    } yield ()
    t.replicateA_(10000)
  }
}
