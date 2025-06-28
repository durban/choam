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
package core

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.{ AtomicReference, AtomicInteger, AtomicLong, AtomicBoolean }

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
        ref1.set1(a + "a")
      }
      tsk1 = rxn1.run[F]
      rxn2 = ref2.set1("y")
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

  final override def munitTimeout: Duration =
    super.munitTimeout * 2

  test("Thread interruption in infinite retry") {
    val never = Rxn.unsafe.retry[Unit]
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
      unsafeRxn = Rxn.unsafe.directRead(ref).flatMap { v =>
        Rxn.pure(42).flatMap { _ =>
          Rxn.unsafe.cas(ref, ov = v, nv = v + 1)
        }
      }
      fib <- F.interruptible {
        unsafeRxn.unsafePerform((), this.mcasImpl)
      }.start
      _ <- F.sleep(0.5.second)
      _ <- fib.cancel
      _ <- assertResultF(ref.get.run[F], n + 1) // no change
      // but it *seems* to work with small numbers:
      _ <- ref.getAndSet.run[F](42)
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
        Rxn.unsafe.directRead(ref2).flatMap { unsafeValue =>
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

  test("Zombie side effect") {
    val tsk = for {
      ref1 <- Ref(0L).run[F]
      ref2 <- Ref(0L).run[F]
      sideChannel <- F.delay(new AtomicLong(0L))
      writer = (ref1.update(_ + 1L) *> ref2.update(_ + 1L)).run[F]
      reader = Ref.consistentRead(ref1, ref2).map { v12 =>
        if (v12._1 != v12._2) {
          sideChannel.getAndIncrement()
        }
        v12
      }.run[F]
      v12 <- F.both(F.cede *> writer, F.cede *> reader).map(_._2)
      _ <- assertEqualsF(v12._1, v12._2)
      _ <- assertResultF(F.delay(sideChannel.get()), 0L)
    } yield ()
    F.replicateA(10000, tsk)
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

  test("unsafe.unread should make a conflict disappear") {
    val N = 40000
    def withoutUnread(r1: Ref[String], r2: Ref[String], r3: Ref[String]): Axn[Int] = {
      // without unread, this will sometimes retry if
      // there is a concurrent change to `r1`, and will
      // return `2`:
      (r1.get *> r2.update(_ + "x") *> r3.update(_ + "y")).as(1) + Axn.pure(2)
    }
    def withUnread(r1: Ref[String], r2: Ref[String], r3: Ref[String]): Axn[Int] = {
      // with unread, this must never retry, so must
      // always return `1`:
      (r1.get *> r2.update(_ + "x") *> r3.update(_ + "y") *> Rxn.unsafe.unread(r1)).as(1) + Axn.pure(2)
    }
    def tst(withOrWithout: (Ref[String], Ref[String], Ref[String]) => Axn[Int]): F[Int] = for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("b").run[F]
      r3 <- Ref("c").run[F]
      r <- F.both(
        F.cede *> withOrWithout(r1, r2, r3).run[F], // txn1
        F.cede *> r1.update(_ + "x").run[F], // txn2
        // if txn1 unreads r1, then txn1 and
        // txn2 are disjoint transactions
      ).map(_._1)
    } yield r

    if (this.isEmcas) {
      for {
        _ <- F.cede
        _ <- assertResultF(tst(withoutUnread).replicateA(N).map(_.toSet), Set(1, 2))
        _ <- F.cede
        _ <- assertResultF(tst(withUnread).replicateA(N).map(_.toSet), Set(1))
      } yield ()
    } else {
      // MCAS impls other than EMCAS have a
      // global-version-CAS, so the 2 txns
      // are not really disjoint, so they
      // can have a conflict even when
      // using unread.
      this.assumeF(false)
    }
  }

  test("read, then unsafe.unread, then 2 reads (only last 2 must be consistent)") {
    val t = for {
      r1 <- Ref(0).run[F]
      r <- F.both(
        F.cede *> r1.get.flatMapF { v0 =>
          Rxn.unsafe.unread(r1) *> r1.get.flatMapF { v1 =>
            r1.get.map { v2 => (v0, v1, v2) }
          }
        }.run,
        F.cede *> r1.update(_ + 1).run *> r1.update(_ + 1).run,
      ).map(_._1)
      _ <- assertEqualsF(r._2, r._3) // r._1 is allowed to be different
    } yield ()
    t.replicateA_(20000)
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
      rxn2 = r2.set0.provide("y")
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
      rxn2 = r1.set1("c")
      tsk2 = F.delay(latch1.await()) *> rxn2.run[F] *> F.delay(latch2.countDown())
      v1 <- F.both(tsk1, tsk2).map(_._1)
      _ <- assertEqualsF(v1, "c")
      _ <- assertResultF(r1.get.run[F], "c")
      _ <- assertResultF(F.delay(ctr.get()), 2)
    } yield ()
  }

  test("unsafe.tentativeRead opacity (1)") {
    for {
      r1 <- Ref(0).run[F]
      r2 <- Ref(0).run[F]
      ctr <- F.delay(new AtomicInteger(0))
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      rxn1 = Rxn.unsafe.tentativeRead(r1).flatMapF { ticket1 =>
        ctr.incrementAndGet()
        latch1.countDown()
        // concurrent change to r2
        latch2.await()
        // this will need to retry, because we mustn't extend the log:
        Rxn.unsafe.tentativeRead(r2).flatMapF { ticket2 =>
          ticket2.unsafeSet(99).as((ticket1.unsafePeek, ticket2.unsafePeek))
        }
      }
      rxn2Task = F.delay(latch1.await()) *> r2.update(_ + 1).run[F] *> F.delay(latch2.countDown())
      _ <- assertResultF(F.both(rxn1.run[F], rxn2Task).map(_._1), (0, 1))
      _ <- assertResultF(r1.get.run[F], 0)
      _ <- assertResultF(r2.get.run[F], 99)
      _ <- assertResultF(F.delay(ctr.get()), 2)
    } yield ()
  }

  test("unsafe.tentativeRead opacity (2)") {
    for {
      r0 <- Ref(0).run[F]
      r1 <- Ref(0).run[F]
      r2 <- Ref(0).run[F]
      ctr <- F.delay(new AtomicInteger(0))
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      rxn1 = Rxn.unsafe.tentativeRead(r1).flatMapF { ticket1 =>
        ctr.incrementAndGet()
        latch1.countDown()
        // concurrent change to r0
        latch2.await()
        Rxn.unsafe.tentativeRead(r2).flatMapF { ticket2 =>
          ticket2.unsafeSet(99).as((ticket1.unsafePeek, ticket2.unsafePeek))
        } <* r0.update(_ + 42) // <- this will need to retry, because we mustn't extend the log
      }
      rxn2Task = F.delay(latch1.await()) *> r0.update(_ + 1).run[F] *> F.delay(latch2.countDown())
      _ <- assertResultF(F.both(rxn1.run[F], rxn2Task).map(_._1), (0, 0))
      _ <- assertResultF(r0.get.run[F], 43)
      _ <- assertResultF(r1.get.run[F], 0)
      _ <- assertResultF(r2.get.run[F], 99)
      _ <- assertResultF(F.delay(ctr.get()), 2)
    } yield ()
  }

  test("unsafe.tentativeRead can commit while depending on inconsistent state (that's why it's unsafe)") {
    for {
      _ <- this.assumeF(this.isEmcas)
      r1 <- Ref(0).run[F]
      r2 <- Ref(0).run[F]
      ctr <- F.delay(new AtomicInteger(0))
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      rxn1 = Rxn.unsafe.tentativeRead(r1).flatMapF { ticket1 =>
        Rxn.unsafe.tentativeRead(r2).flatMapF { ticket2 =>
          ctr.incrementAndGet()
          latch1.countDown()
          // concurrent change to r2
          latch2.await()
          ticket1.unsafeSet(99).as((ticket1.unsafePeek, ticket2.unsafePeek))
        }
      }
      rxn2Task = F.delay(latch1.await()) *> r2.update(_ + 1).run[F] *> F.delay(latch2.countDown())
      _ <- assertResultF(F.both(rxn1.run[F], rxn2Task).map(_._1), (0, 0))
      _ <- assertResultF(r1.get.run[F], 99)
      _ <- assertResultF(r2.get.run[F], 1)
      _ <- assertResultF(F.delay(ctr.get()), 1)
    } yield ()
  }

  test("unsafe.tentativeRead (can commit while depending on inconsistent state, but) still detects inconsistent WRITES") {
    for {
      r1 <- Ref(0).run[F]
      r2 <- Ref(0).run[F]
      ctr <- F.delay(new AtomicInteger(0))
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      rxn1 = Rxn.unsafe.tentativeRead(r1).flatMapF { ticket1 =>
        Rxn.unsafe.tentativeRead(r2).flatMapF { ticket2 =>
          ctr.incrementAndGet()
          latch1.countDown()
          // concurrent change to r2
          latch2.await()
          ticket2.unsafeSet(99).as((ticket1.unsafePeek, ticket2.unsafePeek))
        }
      }
      rxn2Task = F.delay(latch1.await()) *> r2.update(_ + 1).run[F] *> F.delay(latch2.countDown())
      _ <- assertResultF(F.both(rxn1.run[F], rxn2Task).map(_._1), (0, 1))
      _ <- assertResultF(r1.get.run[F], 0)
      _ <- assertResultF(r2.get.run[F], 99)
      _ <- assertResultF(F.delay(ctr.get()), 2)
    } yield ()
  }

  test("unsafe.tentativeRead + Exchanger") {
    val t = for {
      ex <- Rxn.unsafe.exchanger[String, Int].run[F]
      ref0 <- Ref(0).run[F]
      ref1 <- Ref(0).run[F]
      ref2 <- Ref(0).run[F]
      latch1 <- F.delay(new CountDownLatch(2))
      latch2 <- F.delay(new CountDownLatch(1))
      leftTries <- F.delay(new AtomicInteger)
      left = ref0.update(_ + 1) *> Axn.unsafe.delay {
        latch1.countDown()
        leftTries.getAndIncrement()
        latch2.await()
      } *> ex.exchange.provide("foo").flatMapF { exVal =>
        ref1.getAndUpdate(_ + 1).map { ov =>
          (ov, exVal)
        }
      }
      concurrentUpdate1 = F.blocking(latch1.await()) *> ref1.update(_ + 1).run[F] *> F.delay(latch2.countDown())
      rightTries <- F.delay(new AtomicInteger)
      right = Rxn.unsafe.tentativeRead(ref2) *> Axn.unsafe.delay {
        latch1.countDown()
        rightTries.getAndIncrement()
        latch2.await()
      } *> ex.dual.exchange.provide(42)
      rrr <- F.both(
        concurrentUpdate1,
        F.both(left.run[F], right.run[F])
      )
      _ <- assertEqualsF(rrr._2._1, (1, 42))
      _ <- assertEqualsF(rrr._2._2, "foo")
      // could be more than 2, exchanger is non-deterministic:
      _ <- assertF(leftTries.get() > 1)
      _ <- assertF(rightTries.get() > 1)
    } yield ()
    t.replicateA_(1000)
  }

  test("Opacity with simple .get instead of unsafe.tentativeRead") {
    for {
      r1 <- Ref(0).run[F]
      r2 <- Ref(0).run[F]
      ctr <- F.delay(new AtomicInteger(0))
      latch1 <- F.delay(new CountDownLatch(1))
      latch2 <- F.delay(new CountDownLatch(1))
      rxn1 = r1.get.flatMapF { v1 =>
        ctr.incrementAndGet()
        latch1.countDown()
        // concurrent change to r2
        latch2.await()
        // this will NOT need to retry, because we can extend the log:
        r2.get.flatMapF { v2 =>
          r2.set1(99).as((v1, v2))
        }
      }
      rxn2Task = F.delay(latch1.await()) *> r2.update(_ + 1).run[F] *> F.delay(latch2.countDown())
      _ <- assertResultF(F.both(rxn1.run[F], rxn2Task).map(_._1), (0, 1))
      _ <- assertResultF(r1.get.run[F], 0)
      _ <- assertResultF(r2.get.run[F], 99)
      _ <- assertResultF(F.delay(ctr.get()), 1)
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
      _ <- tsk.parReplicateA_(P)(using cats.effect.instances.spawn.parallelForGenSpawn)
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
      rxn1 = r1.get <* r2.set1("y")
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
      rxn1 = r1.set0.provide("b") *> r2.set1("y")
      rxn2 = r2.get * r1.get
      rss <- F.both(rxn1.run[F], rxn2.run[F])
      _ <- assertF((rss._2 === ("x", "a")) || (rss._2 === ("y", "b")))
      _ <- assertResultF((r1.get * r2.get).run[F], ("b", "y"))
    } yield ()
    t.replicateA_(10000)
  }
}
