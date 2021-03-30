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

import cats.effect.IO

final class RefSpecNaiveKCAS
  extends BaseSpecIO
  with SpecNaiveKCAS
  with RefSpec[IO]

final class RefSpecEMCAS
  extends BaseSpecIO
  with SpecEMCAS
  with RefSpec[IO]

trait RefSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  import React._

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
        if (ov eq "foo") React.ret("bar")
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

  test("BooleanRefOps should provide guard/guardNot") {
    for {
      trueRef <- Ref(true).run[F]
      falseRef <- Ref(false).run[F]
      ft = React.ret(42)
      _ <- assertResultF(trueRef.guard(ft).run, Some(42))
      _ <- assertResultF(trueRef.guardNot(ft).run, None)
      _ <- assertResultF(falseRef.guard(ft).run, None)
      _ <- assertResultF(falseRef.guardNot(ft).run, Some(42))
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
      _ <- assertResultF(r.updateWith(s => React.ret(s + "c")).run[F], ())
      _ <- assertResultF(r.tryUpdate(_ + "d").run[F], true)
      _ <- assertResultF(r.getAndUpdate(_ + "e").run[F], "abcd")
      _ <- assertResultF(r.getAndUpdateWith(s => React.ret(s + "f")).run[F], "abcde")
      _ <- assertResultF(r.updateAndGet(_ + "g").run[F], "abcdefg")
    } yield ()
  }

  test("Ref#modify et. al.") {
    for {
      r <- Ref("a").run[F]
      _ <- assertResultF(r.modify(s => (s + "b", 42)).run[F], 42)
      _ <- assertResultF(r.modifyWith(s => React.ret((s + "c", 43))).run[F], 43)
      _ <- assertResultF(r.tryModify(s => (s + "d", 44)).run[F], Some(44))
      _ <- assertResultF(r.get.run[F], "abcd")
    } yield ()
  }
}
