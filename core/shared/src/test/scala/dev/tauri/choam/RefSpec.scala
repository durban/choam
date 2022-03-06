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

import scala.math.Ordering

import cats.kernel.{ Order, Hash }
import cats.effect.IO

final class RefSpec_Real_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with RefSpec_Real[IO]

final class RefSpec_Arr_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with RefSpec_Arr[IO]

final class RefSpec_Ref2_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with RefSpec_Ref2[IO]

trait RefSpec_Arr[F[_]] extends RefSpec_Real[F] { this: McasImplSpec =>
  override def newRef[A](initial: A): F[RefType[A]] =
    Ref.array(1, initial).run[F].map(_.unsafeGet(0))
}

trait RefSpec_Ref2[F[_]] extends RefSpec_Real[F] { this: McasImplSpec =>
  override def newRef[A](initial: A): F[RefType[A]] =
    Ref.refP2[A, String](initial, "foo").map(_._1).run[F]
}

trait RefSpec_Real[F[_]] extends RefLikeSpec[F] { this: McasImplSpec =>

  import Rxn._

  override type RefType[A] = Ref[A]

  override def newRef[A](initial: A): F[RefType[A]] =
    Ref(initial).run[F]

  test("Simple CAS should work as expected") {
    for {
      ref <- newRef("ert")
      rea = lift((_: Int).toString) × (ref.unsafeCas("ert", "xyz") >>> lift(_ => "boo"))
      s12 <- rea((5, ()))
      (s1, s2) = s12
      _ <- assertEqualsF(s1, "5")
      _ <- assertEqualsF(s2, "boo")
      _ <- assertResultF(ref.unsafeDirectRead.run[F], "xyz")
    } yield ()
  }

  // TODO: these are independent tests:

  test("Ref.apply") {
    for {
      i <- (Ref(89).flatMap(_.getAndUpdate(_ + 1))).run[F]
      _ <- assertEqualsF(i, 89)
    } yield ()
  }

  test("Ref.array") {
    for {
      a <- Ref.array[String](size = 4, initial = "x").run[F]
      _ <- a.unsafeGet(0).getAndSet("a")
      _ <- a.unsafeGet(1).getAndSet("b")
      _ <- a.unsafeGet(2).getAndSet("c")
      _ <- a.unsafeGet(3).getAndSet("d")
      sw02 = Rxn.swap(a.unsafeGet(0), a.unsafeGet(2))
      sw13 = Rxn.swap(a.unsafeGet(1), a.unsafeGet(3))
      _ <- (sw02 * sw13).run[F]
      _ <- assertResultF(a.unsafeGet(0).get.run[F], "c")
      _ <- assertResultF(a.unsafeGet(1).get.run[F], "d")
      _ <- assertResultF(a.unsafeGet(2).get.run[F], "a")
      _ <- assertResultF(a.unsafeGet(3).get.run[F], "b")
    } yield ()
  }

  test("Ref.array (big)") {
    val N = 0xffff
    for {
      a <- Ref.array[String](size = N, initial = "x").run[F]
      _ <- assertResultF(a.unsafeGet(0).get.run[F], "x")
      _ <- assertResultF(a.unsafeGet(1).get.run[F], "x")
      _ <- assertResultF(a.unsafeGet(2).get.run[F], "x")
      _ <- assertResultF(a.unsafeGet(3).get.run[F], "x")
      _ <- assertResultF(a.unsafeGet(N - 1).get.run[F], "x")
      idx <- F.delay {
        val tlr = java.util.concurrent.ThreadLocalRandom.current()
        tlr.nextInt().abs % N
      }
      _ <- assertResultF(a.unsafeGet(idx).get.run[F], "x")
      _ <- a.unsafeGet(idx).getAndSet[F]("foo")
      _ <- if (idx != 0) {
        assertResultF(a.unsafeGet(0).get.run[F], "x")
      } else F.unit
      _ <- assertResultF(a.unsafeGet(idx).get.run[F], "foo")
      _ <- if (idx != (N - 1)) {
        assertResultF(a.unsafeGet(N - 1).get.run[F], "x")
      } else F.unit
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

trait RefLikeSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  import Rxn._

  type RefType[A] <: RefLike[A]

  def newRef[A](initial: A): F[RefType[A]]

  test("updWith should behave correctly when used through getAndUpdateWith") {
    for {
      r1 <- newRef("foo")
      r2 <- newRef("x")
      r = r1.getAndUpdateWith { ov =>
        if (ov eq "foo") Rxn.ret("bar")
        else r2.upd[Any, String] { (o2, _) => (ov, o2) }
      }
      _ <- r.run
      _ <- assertResultF(r1.get.run, "bar")
      _ <- assertResultF(r2.get.run, "x")
      _ <- r.run
      _ <- assertResultF(r1.get.run, "x")
      _ <- assertResultF(r2.get.run, "bar")
    } yield ()
  }

  test("ifM instead of guard/guardNot") {
    for {
      trueRef <- newRef(true)
      falseRef <- newRef(false)
      ft = Rxn.pure[Option[Int]](Some(42))
      ff = Rxn.pure[Option[Int]](None)
      _ <- assertResultF(trueRef.get.ifM(ft, ff).run, Some(42))
      _ <- assertResultF(trueRef.get.ifM(ff, ft).run, None)
      _ <- assertResultF(falseRef.get.ifM(ft, ff).run, None)
      _ <- assertResultF(falseRef.get.ifM(ff, ft).run, Some(42))
    } yield ()
  }

  test("Ref#get") {
    for {
      a <- newRef("foo")
      b <- newRef(42)
      _ <- assertResultF((a.get * b.get).run[F], ("foo", 42))
      rxn = (a.get * b.get).flatMapF { ab =>
        a.update(_ + "bar") *> a.get.map(a2 => (ab, a2))
      }
      _ <- assertResultF(rxn.run[F], (("foo", 42), "foobar"))
    } yield ()
  }

  test("Ref#update et. al.") {
    for {
      r <- newRef("a")
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
      x <- newRef[Int](1)
      y <- newRef[Int](2)
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
      r <- newRef("a")
      _ <- assertResultF(r.modify(s => (s + "b", 42)).run[F], 42)
      _ <- assertResultF(r.modifyWith(s => Rxn.ret((s + "c", 43))).run[F], 43)
      _ <- assertResultF(r.tryModify(s => (s + "d", 44)).run[F], Some(44))
      _ <- assertResultF(r.get.run[F], "abcd")
    } yield ()
  }

  test("Ref#toCats") {
    for {
      r <- newRef("a")
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
}
