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

import java.util.concurrent.atomic.AtomicBoolean

import cats.kernel.Monoid
import cats.{ ~>, Defer, Monad, StackSafeMonad }
import cats.effect.kernel.Unique
import cats.effect.IO

import core.RetryStrategy

final class TxnSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with TxnSpec[IO]

final class TxnSpec_DefaultMcas_ZIO
  extends BaseSpecZIO
  with SpecDefaultMcas
  with TxnSpec[zio.Task]

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

  test("Txn.panic") {
    val exc = new MyException
    for {
      _ <- assertResultF(Txn.panic(exc).commit.attempt, Left(exc))
      _ <- assertResultF((Txn.panic(exc) orElse Txn.pure[Int](42)).commit.attempt, Left(exc))
      _ <- assertResultF((Txn.retry orElse Txn.panic(exc)).commit.attempt, Left(exc))
      ref <- TRef[Int](0).commit
      _ <- assertResultF((ref.update(_ + 1) *> Txn.panic(exc)).commit.attempt, Left(exc))
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
      txn1 = Txn.unsafe.withLocal(42, new Txn.unsafe.WithLocal[Int, String] {
        final override def apply[G[_]](local: TxnLocal[G, Int], lift: Txn ~> G, inst: TxnLocal.Instances[G]) = {
          import inst._
          local.get.flatMap { ov =>
            lift(ref.set(ov)) *> local.set(99).as("foo")
          }
        }
      })
      _ <- assertResultF(txn1.map(_ + "bar").commit, "foobar")
      _ <- assertResultF(ref.get.commit, 42)
    } yield ()
  }

  test("TxnLocal (compose with Txn)") {
    val txn: Txn[(String, Int)] = for {
      ref <- TRef[Int](0)
      s <- Txn.unsafe.withLocal(42, new Txn.unsafe.WithLocal[Int, String] {
        final override def apply[G[_]](scratch: TxnLocal[G, Int], lift: Txn ~> G, inst: TxnLocal.Instances[G]) = {
          import inst.monadInstance
          for {
            i <- lift(ref.get)
            _ <- scratch.set(i)
            _ <- scratch.update(_ + 1)
            v <- scratch.get
            _ <- lift(ref.set(v))
          } yield ""
        }
      })
      v <- ref.get
    } yield (s, v)
    assertResultF(txn.commit, ("", 1))
  }

  test("TxnLocal (rollback)") {
    for {
      ref <- TRef[Int](0).commit
      v <- Txn.unsafe.withLocal(0, new Txn.unsafe.WithLocal[Int, Int] {
        final override def apply[G[_]](
          local: TxnLocal[G, Int],
          lift: Txn ~> G,
          inst: TxnLocal.Instances[G],
        ) = {
          import inst.monadInstance
          lift(Txn.unsafe.plus(Txn.pure(0), Txn.pure(1))).flatMap { leftOrRight =>
            lift(ref.update(_ + 1)) *> local.getAndUpdate(_ + 1).flatMap { ov =>
              if (leftOrRight == 0) { // left
                if (ov == 0) { // ok
                  lift(Txn.unsafe.retryUnconditionally) // go to right
                } else {
                  lift(Txn.panic(new AssertionError))
                }
              } else { // right
                if (ov == 0) { // ok
                  lift(ref.get)
                } else {
                  lift(Txn.panic(new AssertionError))
                }
              }
            }
          }
        }
      }).commit
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.commit, 1)
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

  test("Unique[Txn[F, *]] instance") {
    def generic[G[_]](implicit G: Unique[G]): G[(Unique.Token, Unique.Token)] = {
      G.applicative.map2(G.unique, G.unique)(Tuple2.apply)
    }
    for {
      t12 <- generic[Txn].commit
      (t1, t2) = t12
      _ <- assertNotEqualsF(t1, t2)
    } yield ()
  }
}
