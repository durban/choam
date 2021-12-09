/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.math.Ordering

import cats.kernel.{ Order, Hash }
import cats.effect.IO

final class RefSpec_ThreadConfinedMCAS_IO
  extends BaseSpecIO
  with SpecThreadConfinedMCAS
  with RefSpec[IO]

trait RefSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  import Rxn._

  test("Simple CAS should work as expected") {
    for {
      ref <- Ref("ert").run[F]
      rea = lift((_: Int).toString) × (ref.unsafeCas("ert", "xyz") >>> lift(_ => "boo"))
      s12 <- rea((5, ()))
      (s1, s2) = s12
      _ <- assertEqualsF(s1, "5")
      _ <- assertEqualsF(s2, "boo")
      _ <- assertResultF(ref.unsafeInvisibleRead.run[F], "xyz")
    } yield ()
  }

  test("updWith should behave correctly when used through getAndUpdateWith") {
    for {
      r1 <- Ref("foo").run[F]
      r2 <- Ref("x").run[F]
      r = r1.getAndUpdateWith { ov =>
        if (ov eq "foo") Rxn.ret("bar")
        else r2.upd[Any, String] { (o2, _) => (ov, o2) }
      }
      _ <- r.run
      _ <- assertResultF(r1.unsafeInvisibleRead.run, "bar")
      _ <- assertResultF(r2.unsafeInvisibleRead.run, "x")
      _ <- r.run
      _ <- assertResultF(r1.unsafeInvisibleRead.run, "x")
      _ <- assertResultF(r2.unsafeInvisibleRead.run, "bar")
    } yield ()
  }

  test("ifM instead of guard/guardNot") {
    for {
      trueRef <- Ref(true).run[F]
      falseRef <- Ref(false).run[F]
      ft = Rxn.pure[Option[Int]](Some(42))
      ff = Rxn.pure[Option[Int]](None)
      _ <- assertResultF(trueRef.get.ifM(ft, ff).run, Some(42))
      _ <- assertResultF(trueRef.get.ifM(ff, ft).run, None)
      _ <- assertResultF(falseRef.get.ifM(ft, ff).run, None)
      _ <- assertResultF(falseRef.get.ifM(ff, ft).run, Some(42))
    } yield ()
  }

  test("Ref.apply") {
    for {
      i <- (Ref(89).flatMap(_.getAndUpdate(_ + 1))).run[F]
      _ <- assertEqualsF(i, 89)
    } yield ()
  }

  test("Ref#update et. al.") {
    for {
      r <- Ref("a").run[F]
      _ <- assertResultF(r.update(_ + "b").run[F], ())
      _ <- assertResultF(r.updateWith(s => Rxn.ret(s + "c")).run[F], ())
      _ <- assertResultF(r.tryUpdate(_ + "d").run[F], true)
      _ <- assertResultF(r.getAndUpdate(_ + "e").run[F], "abcd")
      _ <- assertResultF(r.getAndUpdateWith(s => Rxn.ret(s + "f")).run[F], "abcde")
      _ <- assertResultF(r.updateAndGet(_ + "g").run[F], "abcdefg")
    } yield ()
  }

  test("Ref#update example") {
    for {
      x <- Ref[Int](1).run[F]
      y <- Ref[Int](2).run[F]
      _ <- assertResultF(
        (x.update(_ + 1) >>> y.update(_ + 1)).run[F],
        ()
      )
      _ <- assertResultF(x.get.run[F], 2)
      _ <- assertResultF(y.get.run[F], 3)
    } yield ()
  }

  test("Ref#modify et. al.") {
    for {
      r <- Ref("a").run[F]
      _ <- assertResultF(r.modify(s => (s + "b", 42)).run[F], 42)
      _ <- assertResultF(r.modifyWith(s => Rxn.ret((s + "c", 43))).run[F], 43)
      _ <- assertResultF(r.tryModify(s => (s + "d", 44)).run[F], Some(44))
      _ <- assertResultF(r.get.run[F], "abcd")
    } yield ()
  }

  test("Ref#toCats") {
    for {
      r <- Ref("a").run[F]
      cr = r.toCats
      _ <- assertResultF(cr.get, "a")
      _ <- cr.set("b")
      _ <- assertResultF(r.get.run[F], "b")
      _ <- assertResultF(cr.get, "b")
      vs <- cr.access
      (v, s) = vs
      _ <- assertEqualsF(v, "b")
      _ <- assertResultF(s("c"), true)
      _ <- assertResultF(r.get.run[F], "c")
      _ <- assertResultF(cr.get, "c")
      _ <- cr.set("b")
      _ <- assertResultF(s("c"), false) // second call must always fail
      vs <- cr.access
      (v, s) = vs
      _ <- assertEqualsF(v, "b")
      _ <- cr.update(_ + "x")
      _ <- assertResultF(s("c"), false) // concurrent modification
      _ <- assertResultF(r.get.run[F], "bx")
      _ <- assertResultF(cr.get, "bx")
    } yield ()
  }

  test("Ref.array") {
    for {
      a <- Ref.array[String](size = 4, initial = "x").run[F]
      _ <- a(0).getAndSet("a")
      _ <- a(1).getAndSet("b")
      _ <- a(2).getAndSet("c")
      _ <- a(3).getAndSet("d")
      sw02 = Rxn.swap(a(0), a(2))
      sw13 = Rxn.swap(a(1), a(3))
      _ <- (sw02 * sw13).run[F]
      _ <- assertResultF(a(0).get.run[F], "c")
      _ <- assertResultF(a(1).get.run[F], "d")
      _ <- assertResultF(a(2).get.run[F], "a")
      _ <- assertResultF(a(3).get.run[F], "b")
    } yield ()
  }

  test("Ref.array (big)") {
    val N = 0xffff
    for {
      a <- Ref.array[String](size = N, initial = "x").run[F]
      _ <- assertResultF(a(0).get.run[F], "x")
      _ <- assertResultF(a(1).get.run[F], "x")
      _ <- assertResultF(a(2).get.run[F], "x")
      _ <- assertResultF(a(3).get.run[F], "x")
      _ <- assertResultF(a(N - 1).get.run[F], "x")
      idx <- F.delay {
        val tlr = java.util.concurrent.ThreadLocalRandom.current()
        tlr.nextInt().abs % N
      }
      _ <- assertResultF(a(idx).get.run[F], "x")
      _ <- a(idx).getAndSet("foo")
      _ <- assertResultF(a(0).get.run[F], "x")
      _ <- assertResultF(a(idx).get.run[F], "foo")
      _ <- assertResultF(a(N - 1).get.run[F], "x")
    } yield ()
  }

  test("Order/Ordering/Hash instances") {
    Order[Ref[Int]]
    Ordering[Ref[Int]]
    Hash[Ref[Int]]
    def parametric[A]: Int = {
      (Order[Ref[A]].## ^ Ordering[Ref[A]].## ^ Hash[Ref[A]].##).abs
    }
    assert(parametric[String] >= 0)
  }
}