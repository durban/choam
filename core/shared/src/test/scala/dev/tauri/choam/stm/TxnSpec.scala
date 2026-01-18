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
package stm

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

import cats.kernel.Monoid
import cats.{ Defer, Monad, StackSafeMonad, Applicative }
import cats.effect.kernel.Unique
import cats.effect.std.UUIDGen
import cats.effect.IO

final class TxnSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with TxnSpec[IO]

trait TxnSpec[F[_]] extends TxnBaseSpec[F] { this: McasImplSpec =>

  final class MyException extends Exception

  test("Hello World") {
    def txn(r: TRef[Int]): Txn[(Int, Int)] = for {
      v0 <- r.get
      _ <- r.set(99)
      v1 <- r.get
    } yield (v0, v1)

    for {
      r <- TRef[Int](42).commit
      _ <- assertResultF(txn(r).commit, (42, 99))
      _ <- assertResultF(r.get.commit[F], 99)
    } yield ()
  }

  test("Txn#commit should be repeatable") {
    val t: Txn[Int] =
      Txn.pure(42)
    val tsk = t.commit
    assertResultF(tsk.replicateA(3), List(42, 42, 42))
  }

  test("Run with custom RetryStrategy") {
    assertResultF(Transactive[F].commit(Txn.pure(42), RetryStrategy.cede()), 42)
  }

  test("TRef read twice") {
    for {
      r <- TRef[Int](1).commit
      _ <- assertResultF(r.get.flatMap { v1 =>
        r.set(v1 + 1).flatMap { _ =>
          r.get.map { v2 =>
            (v1, v2)
          }
        }
      }.commit, (1, 2))
    } yield ()
  }

  test("Txn#flatten") {
    val inner: Txn[Int] = Txn.pure(42)
    assertResultF(Txn.pure(99).as(inner).flatten.commit, 42)
  }

  test("Txn#map2") {
    for {
      r1 <- TRef[Int](42).commit
      r2 <- TRef[Int](99).commit
      _ <- assertResultF(
        r1.get.map2(r2.get) { _ + _ }.commit,
        42 + 99,
      )
    } yield ()
  }

  test("Txn#as") {
    for {
      r1 <- TRef[Int](42).commit
      _ <- assertResultF(r1.set(99).as(3).commit, 3)
      _ <- assertResultF(r1.get.commit, 99)
    } yield ()
  }

  test("Txn#void") {
    for {
      r1 <- TRef[Int](42).commit
      _ <- assertResultF(r1.set(99).void.commit, ())
      _ <- assertResultF(r1.get.commit, 99)
    } yield ()
  }

  test("Txn#productR/L") {
    for {
      r1 <- TRef[Int](42).commit
      r2 <- TRef[Int](99).commit
      _ <- assertResultF(r1.set(99).productR(r2.get).commit, 99)
      _ <- assertResultF(r1.get.commit, 99)
      _ <- assertResultF(r2.get.productL(r1.set(100)).commit, 99)
      _ <- assertResultF(r1.get.commit, 100)
    } yield ()
  }

  test("Txn#*>/<*") {
    for {
      r1 <- TRef[Int](42).commit
      r2 <- TRef[Int](99).commit
      _ <- assertResultF((r1.set(99) *> (r2.get)).commit, 99)
      _ <- assertResultF(r1.get.commit, 99)
      _ <- assertResultF((r2.get <* r1.set(100)).commit, 99)
      _ <- assertResultF(r1.get.commit, 100)
    } yield ()
  }

  test("Txn.tailRecM") {
    for {
      r <- TRef[Int](0).commit
      _ <- assertResultF(Txn.tailRecM[Int, Int](0) { a =>
        if (a > 10) Txn.pure(Right(a))
        else r.get.flatMap { ov => r.set(ov + 1).as(Left(a + 1)) }
      }.commit, 11)
      _ <- assertResultF(r.get.commit, 11)
    } yield ()
  }

  test("Txn.defer") {
    for {
      flag <- F.delay(new AtomicBoolean)
      r <- TRef[Int](0).commit
      txn = Txn.defer {
        flag.set(true)
        r.get
      }
      _ <- assertResultF(F.delay(flag.get()), false)
      _ <- assertResultF(txn.commit, 0)
      _ <- assertResultF(F.delay(flag.get()), true)
    } yield ()
  }

  test("Txn.merge") {
    val N = 1000
    val K = 10
    val t = for {
      refs <- TRef(0).commit.replicateA(N)
      txns = refs.map { ref =>
        ref.get.flatMap { i =>
          if (i > 0) ref.set(i - 1).as(i)
          else Txn.retry
        }
      }
      merged = Txn.merge(txns)
      fib <- merged.commit.start
      _ <- F.sleep(10.millis)
      idxs <- F.delay(ThreadLocalRandom.current().nextInt(N)).replicateA(K)
      _ <- idxs.parTraverse { idx =>
        val v = idx + 1 // so that it's > 0
        refs(idx).set(v).commit
      }
      r <- fib.joinWithNever
      _ <- assertF(idxs.contains(r - 1))
    } yield ()
    t.replicateA_(this.platform match {
      case Jvm => 50
      case Js => 5
      case Native => 20
    })
  }

  test("Txn.unsafe.panic") {
    val exc = new MyException
    for {
      _ <- assertResultF(Txn.unsafe.panic(exc).commit.attempt, Left(exc))
      _ <- assertResultF((Txn.unsafe.panic(exc) orElse Txn.pure[Int](42)).commit.attempt, Left(exc))
      _ <- assertResultF((Txn.retry orElse Txn.unsafe.panic(exc)).commit.attempt, Left(exc))
      ref <- TRef[Int](0).commit
      _ <- assertResultF((ref.update(_ + 1) *> Txn.unsafe.panic(exc)).commit.attempt, Left(exc))
      _ <- assertResultF(ref.get.commit, 0)
    } yield ()
  }

  test("Txn.unique") {
    for {
      t1 <- Txn.unique.commit
      t23 <- (Txn.unique, Txn.unique).tupled.commit
      (t2, t3) = t23
      _ <- assertEqualsF(Set(t1, t2, t3).size, 3)
    } yield ()
  }

  test("Txn.newUuid") {
    for {
      t1 <- Txn.newUuid.commit
      t23 <- (Txn.newUuid, Txn.newUuid).tupled.commit
      (t2, t3) = t23
      _ <- assertEqualsF(Set(t1, t2, t3).size, 3)
      _ <- F.delay {
        for (u <- List(t1, t2, t3)) {
          assertEquals(u.variant, 2)
          assertEquals(u.version, 4)
        }
      }
    } yield ()
  }

  test("Txn._true, ._false, and .none") {
    for {
      _ <- assertResultF(Txn._true.commit, true)
      _ <- assertResultF(Txn._false.commit, false)
      _ <- assertResultF(Txn.none[Int].commit, None)
    } yield ()
  }

  test("Txn.unsafe.delayContext") {
    Txn.unsafe.delayContext { ctx =>
      ctx eq this.mcasImpl.currentContext()
    }.commit.flatMap(ok => assertF(ok))
  }

  test("Txn.unsafe.suspendContext") {
    Txn.unsafe.suspendContext { ctx =>
      Txn.pure(ctx eq this.mcasImpl.currentContext())
    }.commit.flatMap(ok => assertF(ok))
  }

  test("TxnLocal (simple)") {
    for {
      ref <- TRef[Int](0).commit
      txn1 = for {
        local <- Txn.unsafe.newLocal(42)
        ov <- local.get
        _ <- ref.set(ov)
        _ <- local.set(99)
        nv <- local.get
      } yield ("foo", nv)
      _ <- assertResultF(txn1.map { tup => (tup._1 + "bar", tup._2) }.commit, ("foobar", 99))
      _ <- assertResultF(ref.get.commit, 42)
    } yield ()
  }

  test("TxnLocal (simple, leaked)") {
    for {
      ref <- TRef[Int](0).commit
      local <- Txn.unsafe.newLocal(42).commit
      txn1 = for {
        ov <- local.get
        _ <- ref.set(ov)
        _ <- local.set(99)
        nv <- local.get
      } yield ("foo", nv)
      _ <- assertResultF(txn1.map { tup => (tup._1 + "bar", tup._2) }.commit, ("foobar", 99))
      _ <- assertResultF(ref.get.commit, 42)
    } yield ()
  }

  test("TxnLocal.Array (simple)") {
    for {
      ref <- TRef[(Int, Int, Int)]((0, 0, 0)).commit[F]
      ref2 <- TRef[Int](0).commit
      txn = for {
        arr <- Txn.unsafe.newLocalArray(size = 3, initial = 42)
        ov0 <- arr.unsafeGet(0)
        ov1 <- arr.unsafeGet(1)
        ov2 <- arr.unsafeGet(2)
        _ <- ref.set((ov0, ov1, ov2))
        _ <- arr.unsafeSet(1, 99)
        nv <- arr.unsafeGet(1)
        _ <- ref2.set(nv)
      } yield "foo"
      _ <- assertResultF(txn.map(_ + "bar").commit, "foobar")
      _ <- assertResultF(ref.get.commit, (42, 42, 42))
      _ <- assertResultF(ref2.get.commit, 99)
    } yield ()
  }

  test("TxnLocal.Array (simple, leaked)") {
    for {
      ref <- TRef[(Int, Int, Int)]((0, 0, 0)).commit
      ref2 <- TRef[Int](0).commit
      arr <- Txn.unsafe.newLocalArray(size = 3, initial = 42).commit
      txn = for {
        ov0 <- arr.unsafeGet(0)
        ov1 <- arr.unsafeGet(1)
        ov2 <- arr.unsafeGet(2)
        _ <- ref.set((ov0, ov1, ov2))
        _ <- arr.unsafeSet(1, 99)
        nv <- arr.unsafeGet(1)
        _ <- ref2.set(nv)
      } yield "foo"
      _ <- assertResultF(txn.map(_ + "bar").commit, "foobar")
      _ <- assertResultF(ref.get.commit, (42, 42, 42))
      _ <- assertResultF(ref2.get.commit, 99)
    } yield ()
  }

  test("TxnLocal (compose with Txn)") {
    val txn: Txn[Int] = for {
      ref <- TRef[Int](0)
      scratch <- Txn.unsafe.newLocal(42)
      i <- ref.get
      _ <- scratch.set(i)
      _ <- scratch.update(_ + 1)
      v <- scratch.get
      _ <- ref.set(v)
      v2 <- ref.get
    } yield v2
    assertResultF(txn.commit, 1)
  }

  test("TxnLocal (compose with Txn, leaked)") {
    for {
      scratch <- Txn.unsafe.newLocal(42).commit
      txn = for {
        ref <- TRef[Int](0)
        i <- ref.get
        _ <- scratch.set(i)
        _ <- scratch.update(_ + 1)
        v <- scratch.get
        _ <- ref.set(v)
        v2 <- ref.get
      } yield v2
      _ <- assertResultF(txn.commit, 1)
    } yield ()
  }

  test("TxnLocal (rollback)") {
    for {
      ref <- TRef[Int](0).commit
      v <- (for {
        local <- Txn.unsafe.newLocal(0)
        _ <- local.getAndUpdate(_ + 1)
        leftOrRight <- Txn.unsafe.plus(Txn.pure(0), Txn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- local.getAndUpdate(_ + 1)
        v <- if (leftOrRight == 0) { // left
          if (ov == 1) { // ok
            Txn.unsafe.retryUnconditionally // go to right
          } else {
            Txn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 1) { // ok
            ref.get
          } else {
            Txn.unsafe.panic(new AssertionError)
          }
        }
      } yield v).commit
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.commit, 1)
    } yield ()
  }

  test("TxnLocal (rollback, leaked)") {
    for {
      ref <- TRef[Int](0).commit
      local <- Txn.unsafe.newLocal(0).commit
      v <- (for {
        _ <- local.getAndUpdate(_ + 1)
        leftOrRight <- Txn.unsafe.plus(Txn.pure(0), Txn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- local.getAndUpdate(_ + 1)
        v <- if (leftOrRight == 0) { // left
          if (ov == 1) { // ok
            Txn.unsafe.retryUnconditionally // go to right
          } else {
            Txn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 1) { // ok
            ref.get
          } else {
            Txn.unsafe.panic(new AssertionError)
          }
        }
      } yield v).commit
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.commit, 1)
    } yield ()
  }

  test("TxnLocal (STM rollback)") {
    for {
      ref1 <- TRef[Int](0).commit
      ref2 <- TRef[Int](0).commit
      v <- (for {
        local <- Txn.unsafe.newLocal(0)
        _ <- local.getAndUpdate(_ + 1)
        txnLeft = local.getAndUpdate(_ + 1).flatMap { ovLeft =>
          ref1.set(ovLeft)
        } *> Txn.retry
        txnRight = local.getAndUpdate(_ + 2).flatMap { ovRight =>
          ref2.set(ovRight)
        }
        _ <- txnLeft orElse txnRight
        v <- local.get
      } yield v).commit
      _ <- assertEqualsF(v, 3)
      _ <- assertResultF(ref1.get.commit, 0)
      _ <- assertResultF(ref2.get.commit, 1)
    } yield ()
  }

  test("TxnLocal (STM rollback, leaked)") {
    for {
      ref1 <- TRef[Int](0).commit
      ref2 <- TRef[Int](0).commit
      local <- Txn.unsafe.newLocal(0).commit
      v <- (for {
        _ <- local.getAndUpdate(_ + 1)
        txnLeft = local.getAndUpdate(_ + 1).flatMap { ovLeft =>
          ref1.set(ovLeft)
        } *> Txn.retry
        txnRight = local.getAndUpdate(_ + 2).flatMap { ovRight =>
          ref2.set(ovRight)
        }
        _ <- txnLeft orElse txnRight
        v <- local.get
      } yield v).commit
      _ <- assertEqualsF(v, 3)
      _ <- assertResultF(ref1.get.commit, 0)
      _ <- assertResultF(ref2.get.commit, 1)
    } yield ()
  }

  test("TxnLocal.Array (rollback)") {
    for {
      ref <- TRef[Int](0).commit
      v <- (for {
        arr <- Txn.unsafe.newLocalArray(size = 3, initial = 0)
        ov0 <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov0 + 1)
        leftOrRight <- Txn.unsafe.plus(Txn.pure(0), Txn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov + 1)
        v <- if (leftOrRight == 0) { // left
          if (ov == 1) { // ok
            Txn.unsafe.retryUnconditionally // go to right
          } else {
            Txn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 1) { // ok
            ref.get
          } else {
            Txn.unsafe.panic(new AssertionError)
          }
        }
      } yield v).commit
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.commit, 1)
    } yield ()
  }

  test("TxnLocal.Array (rollback, leaked)") {
    for {
      ref <- TRef[Int](0).commit
      arr <- Txn.unsafe.newLocalArray(size = 3, initial = 0).commit
      v <- (for {
        ov0 <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov0 + 1)
        leftOrRight <- Txn.unsafe.plus(Txn.pure(0), Txn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov + 1)
        v <- if (leftOrRight == 0) { // left
          if (ov == 1) { // ok
            Txn.unsafe.retryUnconditionally // go to right
          } else {
            Txn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 1) { // ok
            ref.get
          } else {
            Txn.unsafe.panic(new AssertionError)
          }
        }
      } yield v).commit
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.commit, 1)
    } yield ()
  }

  test("TxnLocal.Array (STM rollback)") {
    for {
      ref1 <- TRef[Int](0).commit
      ref2 <- TRef[Int](0).commit
      v <- (for {
        arr <- Txn.unsafe.newLocalArray(size = 3, initial = 0)
        ov0 <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov0 + 1)
        leftTxn = arr.unsafeGet(1).flatMap { ovLeft =>
          ref1.set(ovLeft) *> arr.unsafeSet(1, ovLeft + 1)
        } *> Txn.retry
        rightTxn = arr.unsafeGet(1).flatMap { ovRight =>
          ref2.set(ovRight) *> arr.unsafeSet(1, ovRight + 2)
        }
        _ <- leftTxn orElse rightTxn
        v <- arr.unsafeGet(1)
      } yield v).commit
      _ <- assertEqualsF(v, 3)
      _ <- assertResultF(ref1.get.commit, 0)
      _ <- assertResultF(ref2.get.commit, 1)
    } yield ()
  }

  test("TxnLocal.Array (STM rollback, leaked)") {
    for {
      ref1 <- TRef[Int](0).commit
      ref2 <- TRef[Int](0).commit
      arr <- Txn.unsafe.newLocalArray(size = 3, initial = 0).commit
      v <- (for {
        ov0 <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov0 + 1)
        leftTxn = arr.unsafeGet(1).flatMap { ovLeft =>
          ref1.set(ovLeft) *> arr.unsafeSet(1, ovLeft + 1)
        } *> Txn.retry
        rightTxn = arr.unsafeGet(1).flatMap { ovRight =>
          ref2.set(ovRight) *> arr.unsafeSet(1, ovRight + 2)
        }
        _ <- leftTxn orElse rightTxn
        v <- arr.unsafeGet(1)
      } yield v).commit
      _ <- assertEqualsF(v, 3)
      _ <- assertResultF(ref1.get.commit, 0)
      _ <- assertResultF(ref2.get.commit, 1)
    } yield ()
  }

  test("TxnLocal (escaped local must be separate)") {
    for {
      la <- (for {
        local <- Txn.unsafe.newLocal(42)
        arr <- Txn.unsafe.newLocalArray(3, 42)
        _ <- local.set(99)
        _ <- arr.unsafeSet(1, 99)
      } yield (local, arr)).commit
      (local, arr) = la
      _ <- assertResultF(local.get.commit, 42)
      _ <- assertResultF(arr.unsafeGet(1).commit, 42)
    } yield ()
  }

  test("Monad[Txn[F, *]] instance") {
    def generic[G[_]](gi1: G[Int], gi2: G[Int])(implicit G: StackSafeMonad[G]): G[Int] = {
      G.map2(gi1, gi2) { _ + _ }
    }
    for {
      r1 <- TRef[Int](42).commit
      r2 <- TRef[Int](99).commit
      _ <- assertResultF(generic(r1.get, r2.get).commit, 42 + 99)
    } yield ()
  }

  test("Monoid instance") {
    def generic[G: Monoid](g1: G, g2: G): G =
      Monoid[G].combine(g1, g2)

    assertResultF(generic[Txn[String]](Txn.pure("a"), Txn.pure("b")).commit, "ab")
  }

  test("Defer[Txn[F, *]] instance") {
    def generic[G[_]](getAndIncrement: G[Int])(implicit G: Defer[G], GM: Monad[G]): G[Int] = {
      G.fix[Int] { rec =>
        getAndIncrement.flatMap { i =>
          if (i > 4) GM.pure(i)
          else rec
        }
      }
    }
    for {
      r <- TRef[Int](0).commit
      i <- generic[Txn](r.getAndUpdate(_ + 1)).commit
      _ <- assertEqualsF(i, 5)
      _ <- assertResultF(r.get.commit, 6)
    } yield ()
  }

  test("Unique[Txn] instance") {
    def generic[G[_]](implicit G: Unique[G]): G[(Unique.Token, Unique.Token)] = {
      val u = G.unique
      G.applicative.map2(u, u)(Tuple2.apply)
    }
    for {
      t12 <- generic[Txn].commit
      (t1, t2) = t12
      _ <- assertNotEqualsF(t1, t2)
    } yield ()
  }

  test("UUIDGen[Txn] instance") {
    def generic[G[_]](implicit G: Applicative[G], gen: UUIDGen[G]): G[(UUID, UUID)] = {
      val ru = gen.randomUUID
      G.map2(ru, ru)(Tuple2.apply)
    }
    for {
      t12 <- generic[Txn].commit
      (t1, t2) = t12
      _ <- assertNotEqualsF(t1, t2)
    } yield ()
  }
}
