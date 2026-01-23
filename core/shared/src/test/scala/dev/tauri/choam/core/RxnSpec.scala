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

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._

import cats.kernel.Monoid
import cats.{ Applicative, StackSafeMonad, Align, Defer }
import cats.data.{ Ior, NonEmptyList }
import cats.effect.IO
import cats.effect.kernel.{ Ref => CatsRef }

import internal.mcas.Mcas
import unsafe.RxnLocal

final class RxnSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with RxnSpec[IO]

trait RxnSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  import Rxn.{ unsafe => _, _ }

  test("Check MCAS implementation") {
    assertSameInstance(Reactive[F].mcasImpl, this.mcasImpl) // just to be sure
  }

  test("Choice should prefer the first option") {
    for {
      r1 <- Ref("r1").run[F]
      r2 <- Ref("r2").run[F]
      rea = Rxn.unsafe.cas(r1, "r1", "x") + Rxn.unsafe.cas(r2, "r2", "x")
      _ <- assertResultF(rea.run, ())
      _ <- assertResultF(Rxn.unsafe.directRead(r1).run, "x")
      _ <- assertResultF(Rxn.unsafe.directRead(r2).run, "r2")
    } yield ()
  }

  test("Choice should use the second option, if the first is not available") {
    for {
      r1 <- Ref("z").run[F]
      r2 <- Ref("r2").run[F]
      rea = Rxn.unsafe.cas(r1, "r1", "x") + (Rxn.unsafe.cas(r2, "r2", "x") * Rxn.unsafe.cas(r1, "z", "r1")).void
      // r2: "r2" -> "x" AND r1: "z" -> "r1"
      _ <- rea.run
      _ <- assertResultF(Rxn.unsafe.directRead(r2).run, "x")
      _ <- assertResultF(Rxn.unsafe.directRead(r1).run, "r1")
      // r1: "r1" -> "x"
      _ <- rea.run
      _ <- assertResultF(Rxn.unsafe.directRead(r1).run, "x")
      _ <- assertResultF(Rxn.unsafe.directRead(r2).run, "x")
    } yield ()
  }

  test("Inner choice should be used first") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("a").run[F]
      rea1 = (Rxn.unsafe.cas(r1, "-", "b") + Rxn.unsafe.cas(r1, "a", "b")) + Rxn.unsafe.cas(r1, "a", "c")
      rea2 = Rxn.unsafe.cas(r2, "-", "b") + (Rxn.unsafe.cas(r2, "a", "b") + Rxn.unsafe.cas(r2, "a", "c"))
      _ <- rea1.run[F]
      _ <- rea2.run[F]
      _ <- assertResultF(Rxn.unsafe.directRead(r1).run, "b")
      _ <- assertResultF(Rxn.unsafe.directRead(r2).run, "b")
    } yield ()
  }

  test("Choice after *>") {
    for {
      a <- Ref("a").run[F]
      b <- Ref("b").run[F]
      y <- Ref("y").run[F]
      p <- Ref("p").run[F]
      q <- Ref("q").run[F]
      rea = (
        (
          (Rxn.unsafe.cas(a, "a", "aa") + (Rxn.unsafe.cas(b, "b", "bb") *> Rxn.unsafe.delay {
            this.mcasImpl.currentContext().tryPerformSingleCas(y.loc, "y", "-")
          }).void) *> Rxn.unsafe.cas(y, "-", "yy")
        ) +
        (Rxn.unsafe.cas(p, "p", "pp") *> Rxn.unsafe.cas(q, "q", "qq"))
      )
      _ <- assertResultF(F.delay { rea.unsafePerform(this.mcasImpl) }, ())
      _ <- assertResultF(Rxn.unsafe.directRead(a).run, "a")
      _ <- assertResultF(Rxn.unsafe.directRead(b).run, "bb")
      _ <- assertResultF(Rxn.unsafe.directRead(y).run, "yy")
      _ <- assertResultF(Rxn.unsafe.directRead(p).run, "p")
      _ <- assertResultF(Rxn.unsafe.directRead(q).run, "q")
    } yield ()
  }

  test("Multiple writes (also in choice)") {
    for {
      a <- Ref("a").run[F]
      p <- Ref("p").run[F]
      rea = a.update(_ => "b") *> (
        (a.getAndUpdate(_ => "c").flatMap(p.getAndSet) *> Rxn.unsafe.retry) +
        (a.getAndUpdate(_ => "x").flatMap(p.getAndSet))
      )
      _ <- assertResultF(F.delay { rea.unsafePerform(this.mcasImpl) }, "p")
      _ <- assertResultF(Rxn.unsafe.directRead(a).run, "x")
      _ <- assertResultF(Rxn.unsafe.directRead(p).run, "b")
    } yield ()
  }

  test("Choice should perform the correct post-commit actions") {
    for {
      r0 <- Ref("").run[F]
      r1 <- Ref("a").run[F]
      pc1 <- Ref("").run[F]
      pc2 <- Ref("-").run[F]
      pc3 <- Ref("").run[F]
      pc4 <- Ref("").run[F]
      rea = (Rxn.postCommit(pc1.update(_ + "pc1")) *> r0.update(_ + "b")) *> (
        (Rxn.postCommit(pc2.update(_ + "pc2")).postCommit(_ => pc4.update(_ + "-")) *> Rxn.unsafe.cas(r1, "-", "b")) + ( // <- this will fail
          Rxn.postCommit(pc3.update(_ + "pc3")).postCommit(_ => pc4.update(_ + "pc4")) *> Rxn.unsafe.cas(r1, "a", "c")
        )
      )
      _ <- rea.run
      _ <- assertResultF(r0.get.run, "b")
      _ <- assertResultF(r1.get.run, "c")
      _ <- assertResultF(pc1.get.run, "pc1")
      _ <- assertResultF(pc2.get.run, "-")
      _ <- assertResultF(pc3.get.run, "pc3")
      _ <- assertResultF(pc4.get.run, "pc4")
    } yield ()
  }

  test("Choice should work if it's after some other operation") {
    for {
      r1a <- Ref("1a").run[F]
      r1b <- Ref("1b").run[F]
      r2a <- Ref("2a").run[F]
      r2b <- Ref("2b").run[F]
      r3a <- Ref("3a").run[F]
      r3b <- Ref("3b").run[F]
      rea = {
        Rxn.unsafe.cas(r1a, "1a", "xa") *>
        Rxn.unsafe.cas(r1b, "1b", "xb") *>
        (
          (Rxn.unsafe.cas(r2a, "2a", "ya") *> Rxn.unsafe.cas(r2b, "2b", "yb")) +
          (Rxn.unsafe.cas(r3a, "3a", "za") *> Rxn.unsafe.cas(r3b, "3b", "zb"))
        )
      }
      // 1st choice selected:
      _ <- rea.run
      _ <- assertResultF(Rxn.unsafe.directRead(r1a).run, "xa")
      _ <- assertResultF(Rxn.unsafe.directRead(r1b).run, "xb")
      _ <- assertResultF(Rxn.unsafe.directRead(r2a).run, "ya")
      _ <- assertResultF(Rxn.unsafe.directRead(r2b).run, "yb")
      _ <- assertResultF(Rxn.unsafe.directRead(r3a).run, "3a")
      _ <- assertResultF(Rxn.unsafe.directRead(r3b).run, "3b")
      _ <- Rxn.unsafe.cas(r1a, "xa", "1a").run
      _ <- Rxn.unsafe.cas(r1b, "xb", "1b").run
      _ <- assertResultF(Rxn.unsafe.directRead(r1a).run, "1a")
      _ <- assertResultF(Rxn.unsafe.directRead(r1b).run, "1b")
      // 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(Rxn.unsafe.directRead(r1a).run, "xa")
      _ <- assertResultF(Rxn.unsafe.directRead(r1b).run, "xb")
      _ <- assertResultF(Rxn.unsafe.directRead(r2a).run, "ya")
      _ <- assertResultF(Rxn.unsafe.directRead(r2b).run, "yb")
      _ <- assertResultF(Rxn.unsafe.directRead(r3a).run, "za")
      _ <- assertResultF(Rxn.unsafe.directRead(r3b).run, "zb")
    } yield ()
  }

  test("Choice should work even if it's computed") {
    for {
      r1a <- Ref("1a").run[F]
      r1b <- Ref("1b").run[F]
      r2a <- Ref("2a").run[F]
      r2b <- Ref("2b").run[F]
      r3a <- Ref("3a").run[F]
      r3b <- Ref("3b").run[F]
      rea = {
        Rxn.unsafe.directRead(r1a).flatMap { s =>
          if (s eq "1a") {
            Rxn.unsafe.cas(r1b, "1b", "xb") *> (Rxn.unsafe.cas(r2a, "2a", "ya") + Rxn.unsafe.cas(r3a, "3a", "za"))
          } else {
            Rxn.unsafe.cas(r1b, "1b", "xx") *> (Rxn.unsafe.cas(r2b, "2b", "yb") + Rxn.unsafe.cas(r3b, "3b", "zb"))
          }
        }
      }

      // THEN selected, 1st choice selected:
      _ <- rea.run
      _ <- assertResultF(Rxn.unsafe.directRead(r1a).run, "1a")
      _ <- assertResultF(Rxn.unsafe.directRead(r1b).run, "xb")
      _ <- assertResultF(Rxn.unsafe.directRead(r2a).run, "ya")
      _ <- assertResultF(Rxn.unsafe.directRead(r2b).run, "2b")
      _ <- assertResultF(Rxn.unsafe.directRead(r3a).run, "3a")
      _ <- assertResultF(Rxn.unsafe.directRead(r3b).run, "3b")

      _ <- Rxn.unsafe.cas(r1b, "xb", "1b").run

      // THEN selected, 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(Rxn.unsafe.directRead(r1a).run, "1a")
      _ <- assertResultF(Rxn.unsafe.directRead(r1b).run, "xb")
      _ <- assertResultF(Rxn.unsafe.directRead(r2a).run, "ya")
      _ <- assertResultF(Rxn.unsafe.directRead(r2b).run, "2b")
      _ <- assertResultF(Rxn.unsafe.directRead(r3a).run, "za")
      _ <- assertResultF(Rxn.unsafe.directRead(r3b).run, "3b")

      _ <- Rxn.unsafe.cas(r1a, "1a", "xa").run
      _ <- Rxn.unsafe.cas(r1b, "xb", "1b").run

      // ELSE selected, 1st choice selected:
      _ <- rea.run
      _ <- assertResultF(Rxn.unsafe.directRead(r1a).run, "xa")
      _ <- assertResultF(Rxn.unsafe.directRead(r1b).run, "xx")
      _ <- assertResultF(Rxn.unsafe.directRead(r2a).run, "ya")
      _ <- assertResultF(Rxn.unsafe.directRead(r2b).run, "yb")
      _ <- assertResultF(Rxn.unsafe.directRead(r3a).run, "za")
      _ <- assertResultF(Rxn.unsafe.directRead(r3b).run, "3b")

      _ <- Rxn.unsafe.cas(r1b, "xx", "1b").run

      // ELSE selected, 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(Rxn.unsafe.directRead(r1a).run, "xa")
      _ <- assertResultF(Rxn.unsafe.directRead(r1b).run, "xx")
      _ <- assertResultF(Rxn.unsafe.directRead(r2a).run, "ya")
      _ <- assertResultF(Rxn.unsafe.directRead(r2b).run, "yb")
      _ <- assertResultF(Rxn.unsafe.directRead(r3a).run, "za")
      _ <- assertResultF(Rxn.unsafe.directRead(r3b).run, "zb")
    } yield ()
  }

  test("Choice should be stack-safe (even when deeply nested)") {
    val n = 16 * 1024
    for {
      ref <- Ref("foo").run[F]
      successfulCas = Rxn.unsafe.cas(ref, "foo", "bar")
      fails = (1 to n).foldLeft[Rxn[Unit]](Rxn.unsafe.retry) { (r, _) =>
        r + Rxn.unsafe.retry
      }
      r = fails + successfulCas
      _ <- assertResultF(r.run, ())
      _ <- assertResultF(ref.get.run, "bar")
    } yield ()
  }

  test("Choice should be stack-safe (even when deeply nested and doing actual CAS-es)") {
    val n = 16 * 1024
    for {
      ref <- Ref("foo").run[F]
      successfulCas = Rxn.unsafe.cas(ref, "foo", "bar")
      refs <- (1 to n).toList.traverse { _ =>
        Ref("x").run[F]
      }
      fails = refs.foldLeft[Rxn[Unit]](Rxn.unsafe.retry) { (r, ref) =>
        r + Rxn.unsafe.cas(ref, "y", "this will never happen")
      }
      r = fails + successfulCas
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(ref.get.run[F], "bar")
      _ <- refs.traverse { ref =>
        assertResultF(ref.get.run[F], "x")
      }
    } yield ()
  }

  test("Choice should correctly backtrack (1) (no jumps)") {
    backtrackTest1(2)
  }

  test("Choice should correctly backtrack (1) (even with jumps)") {
    backtrackTest1(1024 + 1)
  }

  test("Choice should correctly backtrack (2) (no jumps)") {
    backtrackTest2(2)
  }

  test("Choice should correctly backtrack (2) (even with jumps)") {
    backtrackTest2(1024 / 4)
  }

  /**                +
   *                / \
   *               /   \
   *              /     \
   *             /       \
   *        [CASx_ok1]  CAS_ok4
   *            |
   *            |
   *            +
   *           / \
   *          /   \
   *         /     \
   *        /       \
   *    CASx_ok2  [CAS_ok3]
   *       |
   *       |
   *       +
   *      / \
   *     /   \
   *    /     \
   *   /       \
   * CAS_fail  Retry
   */
  def backtrackTest1(x: Int): F[Unit] = for {
    r1 <- mkOkCASes(x, "foo1", "bar1")
    (okRefs1, ok1) = r1
    r2 <- mkOkCASes(x, "foo2", "bar2")
    (okRefs2, ok2) = r2
    okRef3 <- Ref("foo3").run
    okRef4 <- Ref("foo4").run
    failRef <- Ref("fail").run
    left = ok1 *> ((ok2 *> (Rxn.unsafe.cas(failRef, "x_fail", "y_fail") + Rxn.unsafe.retry)) + Rxn.unsafe.cas(okRef3, "foo3", "bar3"))
    right = Rxn.unsafe.cas(okRef4, "foo4", "bar4")
    r = left + right
    _ <- assertResultF(r.run[F], ())
    _ <- okRefs1.traverse { ref =>
      assertResultF(ref.get.run, "bar1")
    }
    _ <- okRefs2.traverse { ref =>
      assertResultF(ref.get.run[F], "foo2")
    }
    _ <- assertResultF(okRef3.get.run[F], "bar3")
    _ <- assertResultF(okRef4.get.run[F], "foo4")
    _ <- assertResultF(failRef.get.run[F], "fail")
  } yield ()

  /**            +
   *            / \
   *           /   \
   *          /     \
   *         /       \
   *     CASx_ok   CASx_ok
   *        |         |
   *        |         |
   *        +         +
   *       / \       / \
   *             .
   *             .
   *             .
   *     |              |
   * CAS_leaf0  ... CAS_leaf15
   */
  def backtrackTest2(x: Int): F[Unit] = {

    def oneChoice(leftCont: Rxn[Unit], rightCont: Rxn[Unit], x: Int, label: String): F[(Rxn[Unit], F[Unit])] = for {
      _ <- F.unit
      ol = s"old-${label}-left"
      nl = s"new-${label}-left"
      ok1 <- mkOkCASes(x, ol, nl)
      (lRefs, left) = ok1
      or = s"old-${label}-right"
      nr = s"new-${label}-right"
      ok2 <- mkOkCASes(x, or, nr)
      (rRefs, right) = ok2
      reset = {
        lRefs.traverse { ref => ref.update(_ => ol).run[F] }.flatMap { _ =>
          rRefs.traverse { ref => ref.update(_ => or).run[F] }
        }.void
      }
    } yield (((left *> leftCont) + (right *> rightCont)).void, reset)

    for {
      leafs <- (0 until 16).toList.traverse(idx => Ref(s"foo-${idx}").run[F])
      lr1 <- leafs.grouped(2).toList.traverse[F, (Rxn[Unit], F[Unit])] {
        case List(refLeft, refRight) =>
          Rxn.unsafe.directRead(refLeft).run[F].flatMap { ol =>
            Rxn.unsafe.directRead(refRight).run[F].flatMap { or =>
              oneChoice(Rxn.unsafe.cas(refLeft, ol, s"${ol}-new"), Rxn.unsafe.cas(refRight, or, s"${or}-new"), x, "l1")
            }
          }
        case _ =>
          failF()
      }.map(_.toList.unzip)
      (l1, rss1) = lr1
      _ <- assertEqualsF(l1.size, 8)

      lr2 <- l1.grouped(2).toList.traverse[F, (Rxn[Unit], F[Unit])] {
        case List(rl, rr) =>
          oneChoice(rl, rr, x, "l2")
        case _ =>
          failF()
      }.map(_.toList.unzip)
      (l2, rss2) = lr2
      _ <- assertEqualsF(l2.size, 4)

      lr3 <- l2.grouped(2).toList.traverse[F, (Rxn[Unit], F[Unit])] {
        case List(rl, rr) =>
          oneChoice(rl, rr, x, "l3")
        case _ =>
          failF()
      }.map(_.toList.unzip)
      (l3, rss3) = lr3
      _ <- assertEqualsF(l3.size, 2)

      tr <- oneChoice(l3(0), l3(1), x, "top")
      (top, rs) = tr

      reset = {
        rss1.sequence >> rss2.sequence >> rss3.sequence >> rs
      }

      checkLeafs = { (expLastNew: Int) =>
        leafs.zipWithIndex.traverse { case (ref, idx) =>
          val expContents = if (idx <= expLastNew) s"foo-${idx}-new" else s"foo-${idx}"
          assertResultF(Rxn.unsafe.directRead(ref).run[F], expContents)
        }
      }

      _ <- checkLeafs(-1)
      _ <- (0 until leafs.size).toList.traverse { e =>
        top.run[F] >> checkLeafs(e) >> reset
      }
    } yield ()
  }

  def mkOkCASes(n: Int, ov: String, nv: String): F[(List[Ref[String]], Rxn[Unit])] = for {
    ref0 <- Ref(ov).run[F]
    refs <- Ref(ov).run[F].replicateA(n - 1)
    r = refs.foldLeft(Rxn.unsafe.cas(ref0, ov, nv)) { (r, ref) =>
      (r * Rxn.unsafe.cas(ref, ov, nv)).void
    }
  } yield (ref0 +: refs, r)

  test("Choice should be associative") {
    def m(r: Ref[Int]) =
      r.update { v => v + 1 }
    def retryOnce(r: AtomicInteger) = {
      Rxn.unsafe.delay { r.getAndIncrement }.flatMap { ctr =>
        if (ctr > 0) Rxn.unit
        else Rxn.unsafe.retry
      }
    }
    def rxn1(ref1: Ref[Int], ref2: Ref[Int], ref3: Ref[Int], ref4: AtomicInteger) = {
      val orElse = m(ref1) + (m(ref2) + m(ref3))
      orElse >> retryOnce(ref4)
    }
    def rxn2(ref1: Ref[Int], ref2: Ref[Int], ref3: Ref[Int], ref4: AtomicInteger) = {
      val orElse = (m(ref1) + m(ref2)) + m(ref3)
      orElse >> retryOnce(ref4)
    }

    for {
      r11 <- Ref(0).run[F]
      r12 <- Ref(0).run[F]
      r13 <- Ref(0).run[F]
      c1 <- F.delay(new AtomicInteger(0))
      _ <- rxn1(r11, r12, r13, c1).run[F]
      _ <- assertResultF((r11.get * r12.get * r13.get).run[F], ((0, 1), 0))
      _ <- assertResultF(F.delay(c1.get()), 2)
      r21 <- Ref(0).run[F]
      r22 <- Ref(0).run[F]
      r23 <- Ref(0).run[F]
      c2 <- F.delay(new AtomicInteger(0))
      _ <- rxn2(r21, r22, r23, c2).run[F]
      _ <- assertResultF((r21.get * r22.get * r23.get).run[F], ((0, 1), 0))
      _ <- assertResultF(F.delay(c2.get()), 2)
    } yield ()
  }

  test("Post-commit actions should be executed") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("").run
      r3 <- Ref("").run
      r = r1.updateAndGet { s => s + "a" }
      pc1 = r.postCommit(r2.set)
      pc2 = pc1.postCommit(r3.set)

      _ <- assertResultF(pc1.run[F], "aa")
      _ <- assertResultF(Rxn.unsafe.directRead(r1).run[F], "aa")
      _ <- assertResultF(Rxn.unsafe.directRead(r2).run[F], "aa")
      _ <- assertResultF(Rxn.unsafe.directRead(r3).run[F], "")

      _ <- assertResultF(pc2.run[F], "aaa")
      _ <- assertResultF(Rxn.unsafe.directRead(r1).run[F], "aaa")
      _ <- assertResultF(Rxn.unsafe.directRead(r2).run[F], "aaa")
      _ <- assertResultF(Rxn.unsafe.directRead(r3).run[F], "aaa")
    } yield ()
  }

  test("Order of post-commit actions") {
    for {
      log <- Ref(List.empty[String]).run[F]
      r1 <- Ref("a").run[F]
      r2 <- Ref("b").run[F]
      rxn = (
        (r1.update(_ + "a").postCommit(log.update("a" :: _)) *> Rxn.unsafe.retry) + (
          r1.update(_ + "b").postCommit(log.update("b" :: _)).postCommit(log.update("b2" :: _))
        ) *> Rxn.postCommit(log.update("x" :: _)).postCommit(log.update("y" :: _))
      ) * (
        r2.update(_ + "c").postCommit(log.update("z" :: _))
      )
      _ <- rxn.run[F]
      _ <- assertResultF(r1.get.run[F], "ab")
      _ <- assertResultF(r2.get.run[F], "bc")
      _ <- assertResultF(log.get.map(_.reverse).run[F], List("b", "b2", "x", "y", "z"))
    } yield ()
  }

  test("Changes committed  must be visible in post-commit actions") {
    for {
      r1 <- Ref(0).run[F]
      r2 <- Ref(0).run
      save1 <- Ref(-1).run
      save2 <- Ref(-1).run
      save3 <- Ref(-1).run
      r = r1.update(_ + 1).postCommit(
        (r2.update(_ + 1) *> r1.getAndSet(42).flatMap(save1.set)).postCommit(
          (r1.get.flatMap(save2.set)) *> (r2.get.flatMap(save3.set))
        )
      )
      _ <- r.run[F]
      _ <- assertResultF(r1.get.run, 42)
      _ <- assertResultF(r2.get.run, 1)
      _ <- assertResultF(save1.get.run, 1)
      _ <- assertResultF(save2.get.run, 42)
      _ <- assertResultF(save3.get.run, 1)
    } yield ()
  }

  test("Formerly impossible CAS should not cause a runtime error") {
    for {
      ref <- Ref("a").run[F]
      r = ref.update(_ + "b") *> ref.updateAndGet(_ + "x")
      res <- r.run[F].attempt
      _ <- res match {
        case Left(ex) =>
          failF(s"error: $ex")
        case Right(value) =>
          assertEqualsF(value, "abx")
      }
    } yield ()
  }

  test("Integration with IO should work") {
    val act: F[String] = for {
      ref <- Ref[String]("foo").run[F]
      _ <- ref.update { s => s + "bar" }.run[F]
      res <- ref.get.run[F]
    } yield res

    for {
      _ <- assertResultF(act, "foobar")
      _ <- assertResultF(act, "foobar")
    } yield ()
  }

  test("Ref.consistentRead") {
    for {
      r1 <- Ref("abc").run[F]
      r2 <- Ref(42).run[F]
      res <- Ref.consistentRead(r1, r2).run[F]
      _ <- assertEqualsF(res, ("abc", 42))
    } yield ()
  }

  test("Ref.consistentReadMany") {
    for {
      r1 <- Ref("abc").run[F]
      r2 <- Ref("def").run[F]
      r3 <- Ref("ghi").run[F]
      r4 <- Ref("-").run[F]
      res <- Ref.consistentReadMany[String](List(r4, r1, r2, r3)).run[F]
      _ <- assertEqualsF(res, List("-", "abc", "def", "ghi"))
    } yield ()
  }

  test("Ref.swap") {
    for {
      r1 <- Ref("abc").run[F]
      r2 <- Ref("def").run[F]
      _ <- Ref.swap(r1, r2).run[F]
      _ <- assertResultF(Rxn.unsafe.directRead(r1).run[F], "def")
      _ <- assertResultF(Rxn.unsafe.directRead(r2).run[F], "abc")
    } yield ()
  }

  test("flatMap and *>") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("x").run[F]
      _ <- Rxn.unsafe.cas(r1, "a", "b").flatMap { _ => Rxn.unsafe.cas(r2, "x", "y") }.run[F]
      _ <- (Rxn.unsafe.cas(r1, "b", "c") *> Rxn.unsafe.cas(r2, "y", "z")).run[F]
      _ <- assertResultF(r1.get.run[F], "c")
      _ <- assertResultF(r2.get.run[F], "z")
    } yield ()
  }

  test("flatten") {
    val inner: Rxn[Int] = Rxn.pure(42)
    assertResultF(Rxn.pure(99).as(inner).flatten.run, 42)
  }

  test("*> and >>") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("x").run[F]
      _ <- (Rxn.unsafe.cas(r1, "a", "b") *> Rxn.unsafe.cas(r2, "x", "y")).run[F]
      _ <- (Rxn.unsafe.cas(r1, "b", "c") >> Rxn.unsafe.cas(r2, "y", "z")).run[F]
      _ <- assertResultF(r1.get.run[F], "c")
      _ <- assertResultF(r2.get.run[F], "z")
    } yield ()
  }

  test("*> receives the correct input") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("b").run[F]
      res <- (r1.getAndSet("x") *> r2.getAndSet("y")).run[F]
      _ <- assertEqualsF(res, "b")
      _ <- assertResultF(r1.get.run[F], "x")
      _ <- assertResultF(r2.get.run[F], "y")
    } yield ()
  }

  test("Recursive >> stack safety") {
    def foo(i: Int, one: Rxn[Int]): Rxn[Int] = {
      if (i == 0) one
      else one >> foo(i - 1, one)
    }
    val rxn = foo(1024 * 1024, Rxn.pure(0).map(_ + 1))
    assertResultF(rxn.run[F], 1)
  }

  test("<* and *>") {
    for {
      r1 <- Ref("a1").run[F]
      r2 <- Ref("a2").run[F]
      _ <- assertResultF((r1.getAndUpdate(_ + "b") <* r2.getAndUpdate(_ + "c")).run[F], "a1")
      _ <- assertResultF(r1.get.run[F], "a1b")
      _ <- assertResultF(r2.get.run[F], "a2c")
      _ <- assertResultF((r1.getAndUpdate(_ + "b").productL(r2.getAndUpdate(_ + "c"))).run[F], "a1b")
      _ <- assertResultF(r1.get.run[F], "a1bb")
      _ <- assertResultF(r2.get.run[F], "a2cc")
      _ <- assertResultF((r1.getAndUpdate(_ + "b") *> r2.getAndUpdate(_ + "c")).run[F], "a2cc")
      _ <- assertResultF(r1.get.run[F], "a1bbb")
      _ <- assertResultF(r2.get.run[F], "a2ccc")
      _ <- assertResultF((r1.getAndUpdate(_ + "b").productR(r2.getAndUpdate(_ + "c"))).run[F], "a2ccc")
      _ <- assertResultF(r1.get.run[F], "a1bbbb")
      _ <- assertResultF(r2.get.run[F], "a2cccc")
    } yield ()
  }


  test("flatMap") {
    for {
      r <- Ref("x").run[F]
      rxn = r.getAndSet("y").flatMap { x => Rxn.pure(x) }
      _ <- assertResultF(rxn.run[F], "x")
      _ <- assertResultF(Rxn.unsafe.directRead(r).run[F], "y")
    } yield ()
  }

  test("flatMap chain") {
    val l = List("a", "b", "c", "d")
    for {
      refs <- l.traverse { s =>
        Ref(s)
      }.run[F]
      r = for {
        a <- refs(0).getAndSet("x")
        b <- refs(1).getAndSet("x")
        c <- refs(2).getAndSet("x")
        d <- refs(3).getAndSet("x")
      } yield List(a, b, c, d)
      res <- r.run[F]
      _ <- assertEqualsF(res, l)
      nvs <- refs.traverse { ref =>
        ref.get
      }.run[F]
      _ <- assertEqualsF(nvs, List.fill(l.length)("x"))
    } yield ()
  }

  test("tailRecM") {
    val l = List("a", "b", "c", "d")
    for {
      refs <- l.traverse { s =>
        Ref(s)
      }.run[F]
      acc <- Ref(List.empty[String]).run[F]
      r = Rxn.tailRecM(refs) {
        case ref :: tail =>
          ref.getAndSet("x").flatMap { s =>
            acc.update { ov => s :: ov }.as(Left(tail))
          }
        case Nil =>
          Rxn.pure(Right(42))
      }
      res <- r.run[F]
      _ <- assertEqualsF(res, 42)
      nvs <- refs.traverse { ref =>
        ref.get
      }.run[F]
      _ <- assertEqualsF(nvs, List.fill(l.length)("x"))
      _ <- assertResultF(acc.get.run[F], l.reverse)
    } yield ()
  }

  test("as and map") {
    for {
      r <- Ref("x").run[F]
      rxn1 = r.getAndSet("X").as("foo")
      rxn2 = r.getAndSet("Y").map(_ => "bar")
      _ <- assertResultF(rxn1.run[F], "foo")
      _ <- assertResultF(Rxn.unsafe.directRead(r).run[F], "X")
      _ <- assertResultF(rxn2.run[F], "bar")
      _ <- assertResultF(Rxn.unsafe.directRead(r).run[F], "Y")
    } yield ()
  }

  test("map2") {
    for {
      r1 <- Ref("x").run[F]
      r2 <- Ref("a").run[F]
      _ <- assertResultF(r1.get.map2(r2.get) { (s1, s2) => s1 + s2 }.run[F], "xa")
      s1s2 <- r1.update(_ + "z").as("z").map2(
        r2.update(_ + "z").as("z")
      ) { (s1, s2) => (s1, s2) }.run[F]
      _ <- assertSameInstanceF(s1s2._1, s1s2._2)
      _ <- assertEqualsF(s1s2._1, "z")
      _ <- assertResultF(
        (r1.get.map2(r2.get) { (s1, s2) => s1 + s2 }).run[F],
        "xzaz"
      )
    } yield ()
  }

  test("unsafeCas") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = Rxn.unsafe.cas(r, "x", "y")
      _ <- assertResultF(rxn.run[F], ())
      _ <- assertResultF(r.get.run[F], "y")
      _ <- r.getAndSet("a").run[F]
      _ <- assertResultF(rxn.attempt.run[F], None)
      _ <- assertResultF(r.get.run[F], "a")
    } yield ()
  }

  test("unsafeCas after read") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = r.get.flatMap { ov =>
        Rxn.unsafe.cas(r, ov, "y")
      }
      _ <- assertResultF(rxn.run[F], ())
      _ <- assertResultF(r.get.run[F], "y")
      _ <- r.getAndSet("a").run[F]
      _ <- assertResultF(rxn.run[F], ())
      _ <- assertResultF(r.get.run[F], "y")
    } yield ()
  }

  test("unsafeCas after write") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = r.getAndSet("y").flatMap { _ =>
        Rxn.unsafe.cas(r, "y", "z")
      }
      _ <- assertResultF(rxn.run[F], ())
      _ <- assertResultF(r.get.run[F], "z")
      _ <- r.getAndSet("a").run[F]
      _ <- assertResultF(rxn.run[F], ())
      _ <- assertResultF(r.get.run[F], "z")
    } yield ()
  }

  test("upd after read") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = r.get.flatMap { ov =>
        r.update { (s: String) =>
          s.toUpperCase(java.util.Locale.ROOT)
        }.as(ov eq "x")
      }
      _ <- assertResultF(rxn.run[F], true)
      _ <- assertResultF(r.get.run[F], "X")
      _ <- r.getAndSet("a").run[F]
      _ <- assertResultF(rxn.run[F], false)
      _ <- assertResultF(r.get.run[F], "A")
    } yield ()
  }

  test("upd after write") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = r.updateAndGet(_ => "y").flatMap { ov =>
        r.update { (s: String) =>
          s.toUpperCase(java.util.Locale.ROOT)
        }.as(ov eq "x")
      }
      _ <- assertResultF(rxn.run[F], false)
      _ <- assertResultF(r.get.run[F], "Y")
      _ <- r.getAndSet("a").run[F]
      _ <- assertResultF(rxn.run[F], false)
      _ <- assertResultF(r.get.run[F], "Y")
    } yield ()
  }

  test("attempt") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("a").run[F]
      r = Rxn.unsafe.cas(r1, "x", "y").attempt * Rxn.unsafe.cas(r2, "a", "b").?
      _ <- assertResultF(r.run[F], (None, Some(())))
    } yield ()
  }

  test("maybe") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("a").run[F]
      r = Rxn.unsafe.cas(r1, "x", "y").maybe * Rxn.unsafe.cas(r2, "a", "b").maybe
      _ <- assertResultF(r.run[F], (false, true))
    } yield ()
  }

  test("RxnLocal (simple)") {
    for {
      ref <- Ref[Int](0).run[F]
      rxn = for {
        local <- Rxn.unsafe.newLocal(42)
        ov <- local.get
        _ <- ref.set(ov)
        _ <- local.set(99)
        nv <- local.get
      } yield ("foo", nv)
      _ <- assertResultF(rxn.map(tup => (tup._1 + "bar", tup._2)).run, ("foobar", 99))
      _ <- assertResultF(ref.get.run[F], 42)
    } yield ()
  }

  test("RxnLocal (simple, leaked)") {
    for {
      ref <- Ref[Int](0).run[F]
      local <- Rxn.unsafe.newLocal(42).run
      rxn = for {
        ov <- local.get
        _ <- ref.set(ov)
        _ <- local.set(99)
        nv <- local.get
      } yield ("foo", nv)
      _ <- assertResultF(rxn.map(tup => (tup._1 + "bar", tup._2)).run, ("foobar", 99))
      _ <- assertResultF(ref.get.run[F], 42)
    } yield ()
  }

  test("RxnLocal.Array (simple)") {
    for {
      ref <- Ref[(Int, Int, Int)]((0, 0, 0)).run[F]
      ref2 <- Ref[Int](0).run[F]
      rxn = for {
        arr <- Rxn.unsafe.newLocalArray(size = 3, initial = 42)
        ov0 <- arr.unsafeGet(0)
        ov1 <- arr.unsafeGet(1)
        ov2 <- arr.unsafeGet(2)
        _ <- ref.set((ov0, ov1, ov2))
        _ <- arr.unsafeSet(1, 99)
        nv <- arr.unsafeGet(1)
        _ <- ref2.set(nv)
      } yield "foo"
      _ <- assertResultF(rxn.map(_ + "bar").run, "foobar")
      _ <- assertResultF(ref.get.run[F], (42, 42, 42))
      _ <- assertResultF(ref2.get.run[F], 99)
    } yield ()
  }

  test("RxnLocal.Array (simple, leaked)") {
    for {
      ref <- Ref[(Int, Int, Int)]((0, 0, 0)).run[F]
      ref2 <- Ref[Int](0).run[F]
      arr <- Rxn.unsafe.newLocalArray(size = 3, initial = 42).run
      rxn = for {
        ov0 <- arr.unsafeGet(0)
        ov1 <- arr.unsafeGet(1)
        ov2 <- arr.unsafeGet(2)
        _ <- ref.set((ov0, ov1, ov2))
        _ <- arr.unsafeSet(1, 99)
        nv <- arr.unsafeGet(1)
        _ <- ref2.set(nv)
      } yield "foo"
      _ <- assertResultF(rxn.map(_ + "bar").run, "foobar")
      _ <- assertResultF(ref.get.run[F], (42, 42, 42))
      _ <- assertResultF(ref2.get.run[F], 99)
    } yield ()
  }

  test("RxnLocal (compose with Rxn)") {
    val rxn: Rxn[Int] = for {
      ref <- Ref[Int](0)
      scratch <- Rxn.unsafe.newLocal(42)
      i <- ref.get
      _ <- scratch.set(i)
      _ <- scratch.update(_ + 1)
      v0 <- scratch.get
      _ <- ref.set(v0)
      v <- ref.get
    } yield v
    assertResultF(rxn.run[F], 1)
  }

  test("RxnLocal (compose with Rxn, leaked)") {
    def rxn(scratch: RxnLocal[Int]): Rxn[Int] = for {
      ref <- Ref[Int](0)
      i <- ref.get
      _ <- scratch.set(i)
      _ <- scratch.update(_ + 1)
      v0 <- scratch.get
      _ <- ref.set(v0)
      v <- ref.get
    } yield v
    for {
      scratch <- Rxn.unsafe.newLocal(42).run[F]
      _ <- assertResultF(rxn(scratch).run[F], 1)
    } yield ()
  }

  test("RxnLocal (rollback)") {
    for {
      ref <- Ref[Int](0).run[F]
      v <- (for {
        local <- Rxn.unsafe.newLocal(0)
        leftOrRight <- (Rxn.pure(0) + Rxn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- local.getAndUpdate(_ + 1)
        res <- if (leftOrRight == 0) { // left
          if (ov == 0) { // ok
            Rxn.unsafe.retry // go to right
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 0) { // ok
            ref.get
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        }
      } yield res).run
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.run[F], 1)
    } yield ()
  }

  test("RxnLocal (rollback, leaked)") {
    for {
      ref <- Ref[Int](0).run[F]
      local <- Rxn.unsafe.newLocal(0).run
      v <- (for {
        leftOrRight <- (Rxn.pure(0) + Rxn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- local.getAndUpdate(_ + 1)
        res <- if (leftOrRight == 0) { // left
          if (ov == 0) { // ok
            Rxn.unsafe.retry // go to right
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 0) { // ok
            ref.get
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        }
      } yield res).run
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.run[F], 1)
    } yield ()
  }

  test("RxnLocal.Array (rollback)") {
    for {
      ref <- Ref[Int](0).run[F]
      v <- (for {
        arr <- RxnLocal.newLocalArray(size = 3, initial = 0)
        ov0 <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov0 + 1)
        leftOrRight <- (Rxn.pure(0) + Rxn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov + 1)
        v <- if (leftOrRight == 0) { // left
          if (ov == 1) { // ok
            Rxn.unsafe.retry // go to right
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 1) { // ok
            ref.get
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        }
      } yield v).run
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.run[F], 1)
    } yield ()
  }

  test("RxnLocal.Array (rollback, leaked)") {
    for {
      ref <- Ref[Int](0).run[F]
      arr <- RxnLocal.newLocalArray(size = 3, initial = 0).run
      v <- (for {
        ov0 <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov0 + 1)
        leftOrRight <- (Rxn.pure(0) + Rxn.pure(1))
        _ <- ref.update(_ + 1)
        ov <- arr.unsafeGet(1)
        _ <- arr.unsafeSet(1, ov + 1)
        v <- if (leftOrRight == 0) { // left
          if (ov == 1) { // ok
            Rxn.unsafe.retry // go to right
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        } else { // right
          if (ov == 1) { // ok
            ref.get
          } else {
            Rxn.unsafe.panic(new AssertionError)
          }
        }
      } yield v).run
      _ <- assertEqualsF(v, 1)
      _ <- assertResultF(ref.get.run[F], 1)
    } yield ()
  }

  test("RxnLocal (nested)") {
    for {
      ref1 <- Ref[Int](0).run[F]
      ref2 <- Ref[Int](0).run[F]
      rxn1 = for {
        local1 <- Rxn.unsafe.newLocal(42)
        ov1 <- local1.get
        _ <- Rxn.unsafe.assert(ov1 == 42)
        local2 <- Rxn.unsafe.newLocal(99)
        ov2 <- local2.get
        _ <- Rxn.unsafe.assert(ov2 == 99)
        _ <- local2.set(42)
        retry <- Rxn.fastRandom.nextBoolean
        _ <- if (retry) {
          Rxn.unsafe.retry[Unit]
        } else {
          Rxn.unit
        }
        v2 <- local2.get
        _ <- ref2.set(v2)
        _ <- ref1.set(ov1)
        _ <- local1.set(99)
        x <- local1.get
      } yield x.toString
      _ <- assertResultF(rxn1.run, "99")
      _ <- assertResultF(ref1.get.run[F], 42)
      _ <- assertResultF(ref2.get.run[F], 42)
    } yield ()
  }

  test("RxnLocal (nested, leaked)") {
    for {
      ref1 <- Ref[Int](0).run[F]
      ref2 <- Ref[Int](0).run[F]
      local1 <- Rxn.unsafe.newLocal(42).run
      local2 <- Rxn.unsafe.newLocal(99).run
      rxn1 = for {
        ov1 <- local1.get
        _ <- Rxn.unsafe.assert(ov1 == 42)
        ov2 <- local2.get
        _ <- Rxn.unsafe.assert(ov2 == 99)
        _ <- local2.set(42)
        retry <- Rxn.fastRandom.nextBoolean
        _ <- if (retry) {
          Rxn.unsafe.retry[Unit]
        } else {
          Rxn.unit
        }
        v2 <- local2.get
        _ <- ref2.set(v2)
        _ <- ref1.set(ov1)
        _ <- local1.set(99)
        x <- local1.get
      } yield x.toString
      _ <- assertResultF(rxn1.run, "99")
      _ <- assertResultF(ref1.get.run[F], 42)
      _ <- assertResultF(ref2.get.run[F], 42)
    } yield ()
  }

  test("RxnLocal (escaped local must be separate)") {
    for {
      la <- (for {
        local <- Rxn.unsafe.newLocal(42)
        arr <- Rxn.unsafe.newLocalArray(3, 42)
        _ <- local.set(99)
        _ <- arr.unsafeSet(1, 99)
      } yield (local, arr)).run
      (local, arr) = la
      v1v2 <- (for {
        v1 <- local.get
        v2 <- arr.unsafeGet(1)
      } yield (v1, v2)).run
      (v1, v2) = v1v2
      _ <- assertEqualsF(v1, 42)
      _ <- assertEqualsF(v2, 42)
    } yield ()
  }

  test("Rxn.unsafe.delayContext") {
    Rxn.unsafe.delayContext { (tc: Mcas.ThreadContext) =>
      tc eq this.mcasImpl.currentContext()
    }.run[F].flatMap(ok => assertF(ok))
  }

  test("Rxn.unsafe.suspendContext") {
    Rxn.unsafe.suspendContext { ctx =>
      Rxn.pure(ctx eq this.mcasImpl.currentContext())
    }.run[F].flatMap(ok => assertF(ok))
  }

  test("unsafe.ticketRead") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("a").run[F]
      r = Rxn.unsafe.ticketRead(r2).flatMap { ticket =>
        r1.getAndUpdate(_ + ticket.unsafePeek).flatMap { ov =>
          if (ov === "aa") {
            Rxn.unit
          } else {
            ticket.unsafeSet(ticket.unsafePeek + "x")
          }
        }
      }
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r1.get.run[F], "aa")
      _ <- assertResultF(r2.get.run[F], "ax")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r1.get.run[F], "aaax")
      _ <- assertResultF(r2.get.run[F], "ax")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r1.get.run[F], "aaaxax")
      _ <- assertResultF(r2.get.run[F], "axx")
    } yield ()
  }

  test("unsafe.ticketReadArray") {
    for {
      arr <- Ref.array(3, "a").run[F]
      r = Rxn.unsafe.ticketReadArray(arr, 2).flatMap { ticket =>
        arr.unsafeGetAndUpdate(1)(_ + ticket.unsafePeek).flatMap { ov =>
          if (ov === "aa") {
            Rxn.unit
          } else {
            ticket.unsafeSet(ticket.unsafePeek + "x")
          }
        }
      }
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(1).run[F], "aa")
      _ <- assertResultF(arr.unsafeGet(2).run[F], "ax")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(1).run[F], "aaax")
      _ <- assertResultF(arr.unsafeGet(2).run[F], "ax")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(1).run[F], "aaaxax")
      _ <- assertResultF(arr.unsafeGet(2).run[F], "axx")
      _ <- assertResultF(arr.unsafeGet(0).run[F], "a")
    } yield ()
  }

  test("unsafe.ticketRead (already in log)") {
    for {
      r2 <- Ref("a").run[F]
      r = r2.update(_ + "b").flatMap { _ =>
        Rxn.unsafe.ticketRead(r2).flatMap { ticket =>
          ticket.unsafeSet(ticket.unsafePeek + "x")
        }
      }
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r2.get.run[F], "abx")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r2.get.run[F], "abxbx")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r2.get.run[F], "abxbxbx")
    } yield ()
  }

  test("unsafe.ticketReadArray (already in log)") {
    for {
      arr <- Ref.array(3, "a").run[F]
      r = arr.unsafeUpdate(2)(_ + "b").flatMap { _ =>
        Rxn.unsafe.ticketReadArray(arr, 2).flatMap { ticket =>
          ticket.unsafeSet(ticket.unsafePeek + "x")
        }
      }
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(2).run[F], "abx")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(2).run[F], "abxbx")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(2).run[F], "abxbxbx")
      _ <- assertResultF(arr.unsafeGet(1).run[F], "a")
      _ <- assertResultF(arr.unsafeGet(0).run[F], "a")
    } yield ()
  }

  test("unsafe.tentativeRead") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("a").run[F]
      r = Rxn.unsafe.tentativeRead(r2).flatMap { v2 =>
        r1.getAndUpdate(_ + v2).flatMap { ov =>
          if (ov === "aa") {
            Rxn.unit
          } else {
            r2.update { ov =>
              assertEquals(ov, v2)
              ov + "x"
            }
          }
        }
      }
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r1.get.run[F], "aa")
      _ <- assertResultF(r2.get.run[F], "ax")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r1.get.run[F], "aaax")
      _ <- assertResultF(r2.get.run[F], "ax")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r1.get.run[F], "aaaxax")
      _ <- assertResultF(r2.get.run[F], "axx")
    } yield ()
  }

  test("unsafe.tentativeReadArray") {
    for {
      arr <- Ref.array(3, "a").run[F]
      r = Rxn.unsafe.tentativeReadArray(arr, 2).flatMap { v2 =>
        arr.unsafeGetAndUpdate(1)(_ + v2).flatMap { ov =>
          if (ov === "aa") {
            Rxn.unit
          } else {
            arr.unsafeUpdate(2) { ov =>
              assertEquals(ov, v2)
              ov + "x"
            }
          }
        }
      }
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(1).run[F], "aa")
      _ <- assertResultF(arr.unsafeGet(2).run[F], "ax")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(1).run[F], "aaax")
      _ <- assertResultF(arr.unsafeGet(2).run[F], "ax")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(1).run[F], "aaaxax")
      _ <- assertResultF(arr.unsafeGet(2).run[F], "axx")
      _ <- assertResultF(arr.unsafeGet(0).run[F], "a")
    } yield ()
  }

  test("unsafe.tentativeRead (already in log)") {
    for {
      r2 <- Ref("a").run[F]
      r = r2.update(_ + "b").flatMap { _ =>
        Rxn.unsafe.tentativeRead(r2).flatMap { v2 =>
          r2.update { ov =>
            assertEquals(ov, v2)
            ov + "x"
          }
        }
      }
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r2.get.run[F], "abx")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r2.get.run[F], "abxbx")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(r2.get.run[F], "abxbxbx")
    } yield ()
  }

  test("unsafe.tentativeReadArray (already in log)") {
    for {
      arr <- Ref.array(3, "a").run[F]
      r = arr.unsafeUpdate(2)(_ + "b").flatMap { _ =>
        Rxn.unsafe.tentativeReadArray(arr, 2).flatMap { v2 =>
          arr.unsafeUpdate(2) { ov =>
            assertEquals(ov, v2)
            ov + "x"
          }
        }
      }
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(2).run[F], "abx")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(2).run[F], "abxbx")
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(arr.unsafeGet(2).run[F], "abxbxbx")
      _ <- assertResultF(arr.unsafeGet(0).run[F], "a")
      _ <- assertResultF(arr.unsafeGet(1).run[F], "a")
    } yield ()
  }

  test("unsafe.forceValidate (dummy)") {
    for {
      r1 <- Ref("a").run[F]
      _ <- r1.update { _ => "x" }.run[F]
      rxn = r1.get.flatMap { v1 =>
        Rxn.unsafe.forceValidate.flatMap { _ =>
          r1.get.map { v2 => (v1, v2) }
        }
      }
      _ <- assertResultF(rxn.run[F], ("x", "x"))
    } yield ()
  }

  test("unsafe.unread (empty log)") {
    for {
      r1 <- Ref("a").run[F]
      _ <- assertResultF(Rxn.unsafe.unread(r1).run, ())
    } yield ()
  }

  test("unsafe.unread (not in log)") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("b").run[F]
      _ <- assertResultF((r1.update(_ + "x") *> Rxn.unsafe.unread(r2)).run, ())
      _ <- assertResultF(r1.get.run, "ax")
      _ <- assertResultF(r2.get.run, "b")
    } yield ()
  }

  test("unsafe.unread (read-only)") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("b").run[F]
      _ <- assertResultF((r1.get *> r2.update(_ + "x") *> Rxn.unsafe.unread(r1)).run, ())
      _ <- assertResultF(r1.get.run, "a")
      _ <- assertResultF(r2.get.run, "bx")
    } yield ()
  }

  test("unsafe.unread (read-write => exception)") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("b").run[F]
      _ <- assertResultF((r1.get *> r2.update(_ + "x") *> Rxn.unsafe.unread(r2)).run.attempt.map(_.isLeft), true)
      _ <- assertResultF(r1.get.run, "a")
      _ <- assertResultF(r2.get.run, "b")
    } yield ()
  }

  test("panic") {
    val exc = new RxnSpec.MyException(99)
    for {
      _ <- assertResultF(Rxn.unsafe.panic(exc).run[F].attempt, Left(exc))
      _ <- assertResultF(Rxn.unsafe.panic(exc).run[F].attempt, Left(exc))
      _ <- assertResultF((Rxn.unsafe.panic(exc) *> Rxn.unsafe.retry).run[F].attempt, Left(exc))
      _ <- assertResultF((Rxn.unsafe.panic(exc) *> Rxn.unsafe.retry).run[F].attempt, Left(exc))
      _ <- assertResultF((Rxn.unsafe.panic(exc) + Rxn.pure(42)).run[F].attempt, Left(exc))
      _ <- assertResultF((Rxn.unsafe.panic(exc) + Rxn.pure(42)).run[F].attempt, Left(exc))
      _ <- assertResultF((Rxn.unsafe.retry[Int] + Rxn.unsafe.panic[Int](exc)).run[F].attempt, Left(exc))
      _ <- assertResultF((Rxn.unsafe.retry[Int] + Rxn.unsafe.panic[Int](exc)).run[F].attempt, Left(exc))
      _ <- assertResultF(F.delay {
        Rxn.unsafe.panic[Int](exc).unsafePerform(this.mcasImpl)
      }.attempt, Left(exc))
      _ <- assertResultF(F.delay {
        Rxn.unsafe.panic[Int](exc).unsafePerform(this.mcasImpl)
      }.attempt, Left(exc))
    } yield ()
  }

  test("assert") {
    for {
      _ <- assertResultF(Rxn.unsafe.assert(true, "foo").run[F], ())
      _ <- Rxn.unsafe.assert(false, "bar").run[F].attempt.flatMap {
        case Left(ex) => assertF(ex.isInstanceOf[AssertionError]) *> assertEqualsF(ex.getMessage, "bar")
        case Right(a) => failF[Unit](s"unexpected success: ${a}")
      }
    } yield ()
  }

  test("impossibleRxn") {
    Rxn.unsafe.impossibleRxn("foo").run[F].attemptNarrow[AssertionError].flatMap { (e: Either[AssertionError, Nothing]) =>
      assertF(e.isLeft)
    }
  }

  test("panic in post-commit actions (1)") {
    val exc = new RxnSpec.MyException(1)
    for {
      r0 <- Ref(0).run[F]
      r1 <- Ref(0).run[F]
      r2 <- Ref(0).run[F]
      r3 <- Ref(0).run[F]
      res <- r0.getAndUpdate(_ + 1).postCommit(
        (r1.update(_ + 1) *> Rxn.unsafe.panic(exc)).postCommit(r2.update(_ + 1))
      ).postCommit(
        r3.update(_ + 1)
      ).run[F].attempt
      _ <- res match {
        case Left(ex: Rxn.PostCommitException) =>
          assertEqualsF(ex.committedResult, 0) *> assertEqualsF(ex.errors.size, 1) *> assertF(ex.errors.head eq exc)
        case res =>
          failF(s"unexpected result: $res")
      }
      _ <- assertResultF(r0.get.run, 1)
      _ <- assertResultF(r1.get.run, 0)
      _ <- assertResultF(r2.get.run, 0)
      _ <- assertResultF(r3.get.run, 1)
    } yield ()
  }

  test("panic in post-commit actions (2)") {
    val exc = new RxnSpec.MyException(21)
    val exc2 = new RxnSpec.MyException(22)
    for {
      r0 <- Ref(0).run[F]
      r1 <- Ref(0).run[F]
      r2 <- Ref(0).run[F]
      r3 <- Ref(0).run[F]
      r4 <- Ref(0).run[F]
      r5 <- Ref(0).run[F]
      res <- r0.getAndUpdate(_ + 1).postCommit(
        (r1.update(_ + 1) *> Rxn.unsafe.panic(exc)).postCommit(r2.update(_ + 1))
      ).postCommit(
        r3.update(_ + 1)
      ).postCommit(
        r4.update(_ + 1) *> Rxn.unsafe.panic(exc2)
      ).postCommit(
        r5.update(_ + 1)
      ).run[F].attempt
      _ <- res match {
        case Left(ex: Rxn.PostCommitException) =>
          assertEqualsF(ex.committedResult, 0) *> assertEqualsF(ex.errors.size, 2) *> (
            assertF(ex.errors.head eq exc) *> assertF(ex.errors.tail.head eq exc2)
          )
        case res =>
          failF(s"unexpected result: $res")
      }
      _ <- assertResultF(r0.get.run, 1)
      _ <- assertResultF(r1.get.run, 0)
      _ <- assertResultF(r2.get.run, 0)
      _ <- assertResultF(r3.get.run, 1)
      _ <- assertResultF(r4.get.run, 0)
      _ <- assertResultF(r5.get.run, 1)
    } yield ()
  }

  test("unsafe.delay(throw), i.e., unsafe.panic") {
    val exc = new RxnSpec.MyException(42)
    def attemptRun[A](axn: Rxn[A]): F[Either[Throwable, A]] = {
      axn.run[F].attempt
    }
    def assertExc[A](axn: Rxn[A]): F[Unit] = {
      assertResultF(attemptRun(axn), Left(exc))
    }
    for {
      _ <- assertResultF(
        attemptRun[Int](Rxn.unsafe.delay { 42 }),
        Right(42),
      )
      _ <- assertExc((Rxn.unsafe.delay { throw exc }))
      _ <- assertExc(Rxn.unsafe.delay { throw exc } *> Rxn.unsafe.retry)
      _ <- assertExc(Rxn.unsafe.panic(exc) * Rxn.pure(42))
      _ <- assertExc(Rxn.pure(42) * Rxn.unsafe.panic(exc))
      _ <- assertExc(Rxn.tailRecM(0) { i =>
        if (i < 5) Rxn.pure(Left(i + 1))
        else Rxn.unsafe.panic(exc)
      })
      r1 <- Ref(0).run[F]
      _ <- assertExc(Rxn.unsafe.panic(exc).postCommit(r1.update(_ + 1)))
      _ <- assertResultF(r1.get.run, 0)
      r2 <- Ref(0).run[F]
      res <- r2.update(_ + 1).postCommit(Rxn.unsafe.panic(exc)).run[F].attempt
      _ <- res match {
        case Left(_: Rxn.PostCommitException) => F.unit // ok
        case res => failF(s"unexpected result: $res")
      }
      _ <- assertResultF(r2.get.run, 1)
      r3 <- Ref(0).run[F]
      _ <- assertResultF(r3.get.run, 0)
      _ <- assertExc(Rxn.unsafe.panic(exc).as(42))
      _ <- assertExc(Rxn.unsafe.panic(exc) *> Rxn.pure(42))
      _ <- assertExc(Rxn.unsafe.panic[Int](exc).flatMap { _ => Rxn.pure(42) })
      _ <- assertExc(Rxn.unsafe.panic[Int](exc).flatMap { _ => Rxn.pure(42) })
      _ <- assertExc(Rxn.unsafe.panic[Int](exc).as(Rxn.pure(42)).flatten)
      _ <- assertExc(Rxn.unsafe.panic[Int](exc).map { _ => 42 })
      _ <- assertExc(Rxn.unsafe.panic[Int](exc).map2(Rxn.pure(42)) { (_, _) => 42 })
      _ <- assertExc(Rxn.unsafe.orElse(
        Rxn.unsafe.panic(exc) *> Rxn.unsafe.retryStm,
        Rxn.pure(42)
      ))
    } yield ()
  }

  test("Rxn.unsafe.assert") {
    for {
      _ <- assertResultF(Rxn.unsafe.assert(true).run[F], ())
      _ <- assertResultF(Rxn.unsafe.assert(false).run[F].attempt.map(_.isLeft), true)
    } yield ()
  }

  test("Rxn constants") {
    for {
      _ <- assertResultF(Rxn.none[Int].run, None)
      _ <- assertResultF(Rxn.nullOf[String].run, null: String)
      _ <- assertResultF[Int, Int](Rxn.nullOf[Int].run.map(identity), 0: Int) // <- weird because unboxing null
      _ <- assertResultF(Rxn.true_.run, true)
      _ <- assertResultF(Rxn.false_.run, false)
      _ <- assertResultF(Rxn.unit.run, ())
      _ <- assertResultF(Rxn.rightUnit.run, Right(()))
    } yield ()
  }

  test("Autoboxing") {
    // On the JVM integers between (typically) -128 and
    // 127 are cached. Due to autoboxing, other integers
    // may seem to change their "identity". In JS they
    // doesn't seem to change their identity.
    val n = 9999999
    for {
      _ <- if (isIntCached(n)) {
        F.delay(println(s"${n} has stable identity"))
      } else {
        F.delay(println(s"${n} has no stable identity"))
      }
      ref <- Ref[Int](n).run[F]
      // `update` should work fine:
      _ <- ref.update(_ + 1).run[F]
      _ <- assertResultF(ref.get.run[F], n + 1)
      // `unsafeDirectRead` then `unsafeCas` maybe doesn't:
      unsafeRxn = Rxn.unsafe.directRead(ref).flatMap { v =>
        Rxn.pure(42).flatMap { _ =>
          Rxn.unsafe.cas(ref, ov = v, nv = v + 1)
        }
      }
      res <- F.delay {
        unsafeRxn.?.unsafePerform(this.mcasImpl)
      }
      _ <- res match {
        case Some(_) =>
          F.delay(println("unsafeDirectRead / unsafeCas works")) *> (
            assertResultF(ref.get.run[F], n + 2) // worked
          )
        case None =>
          F.delay(println("unsafeDirectRead / unsafeCas doesn't work")) *> (
            assertResultF(ref.get.run[F], n + 1) // no change
          )
      }
      // but it should always work with small numbers:
      _ <- ref.getAndSet(42).run[F]
      _ <- unsafeRxn.run[F]
      _ <- assertResultF(ref.get.run[F], 43)
    } yield ()
  }

  test("Monad instance") {
    def foo[G[_] : StackSafeMonad](ga: G[Int]): G[String] =
      ga.flatMap(x => x.toString.pure[G])

    assertResultF(foo[Rxn](Rxn.pure(42)).run[F], "42")
  }

  test("Monoid instance") {
    def foo[G: Monoid](g1: G, g2: G): G =
      Monoid[G].combine(g1, g2)

    assertResultF(foo[Rxn[String]](Rxn.pure("a"), Rxn.pure("b")).run[F], "ab")
  }

  test("Applicative instance") {
    def foo[G[_] : Applicative](ga: G[Int], gb: G[Int]): G[Int] =
      ga.map2(gb)(_ + _)

    assertResultF(foo(Rxn.pure(21), Rxn.pure(21)).run[F], 42)
  }

  test("Align instance") {
    val inst = Align[Rxn]
    for {
      res1 <- inst.align(Rxn.unsafe.retry[Int], Rxn.pure[Long](42L)).run[F]
      _ <- assertEqualsF(res1, Ior.right(42L))
      res2 <- inst.align(Rxn.pure[Int](42), Rxn.unsafe.retry[Long]).run[F]
      _ <- assertEqualsF(res2, Ior.left(42))
      res3 <- inst.align(Rxn.pure[Int](42), Rxn.pure[Long](23L)).run[F]
      _ <- assertEqualsF(res3, Ior.both(42, 23L))
    } yield ()
  }

  test("Defer instance") {
    val inst = Defer[Rxn]
    for {
      ctr <- F.delay { new AtomicInteger }
      rxn0 = inst.defer {
        ctr.getAndIncrement()
        Rxn.pure("result")
      }
      _ <- assertEqualsF(ctr.get(), 0)
      _ <- assertResultF(rxn0.run[F], "result")
      ref <- Ref("-").run[F]
      rxn1 = inst.fix[Int] { rec =>
        Rxn.unsafe.cas(ref, "a", "b").as(42) + (Rxn.unsafe.delay {
          if (!ref.loc.unsafeCasV("-", "a")) { throw new AssertionError }
        } *> Rxn.unsafe.retry) + rec
      }
      _ <- assertResultF(rxn1.run[F], 42)
      _ <- assertResultF(ref.get.run[F], "b")
    } yield ()
  }

  test("Ref.Make instance") {
    val inst = implicitly[CatsRef.Make[Rxn]]
    val rxn = inst.refOf(42).flatMap { ref =>
      ref.get
    }
    assertResultF(rxn.run[F], 42)
  }

  test("UUIDGen instance") {
    val inst = cats.effect.std.UUIDGen[Rxn]
    for {
      u1 <- inst.randomUUID.run[F]
      _ <- assertEqualsF(u1.version, 4)
      _ <- assertEqualsF(u1.variant, 2)
      u2 <- inst.randomUUID.run[F]
      _ <- assertEqualsF(u2.version, 4)
      _ <- assertEqualsF(u2.variant, 2)
      _ <- assertNotEqualsF(u1, u2)
      _ <- assertNotEqualsF(u1.getLeastSignificantBits, u2.getLeastSignificantBits)
      _ <- assertNotEqualsF(u1.getMostSignificantBits, u2.getMostSignificantBits)
      _ <- assertNotEqualsF(u1.##, u2.##)
    } yield ()
  }

  test("Clock instance") {
    val inst = cats.effect.kernel.Clock[Rxn]
    for {
      t1t2 <- (inst.monotonic * inst.monotonic).run[F]
      _ <- assertF(t1t2._1 <= t1t2._2)
    } yield ()
  }

  private val never = Rxn.unsafe.retry[Int]

  test("maxRetries") {
    def countTries(ctr: AtomicInteger) = {
      Rxn.unsafe.delay { ctr.getAndIncrement() } *> Rxn.unsafe.retry[Int]
    }
    def succeedsOn3rdRetry(ctr: AtomicInteger) = {
      Rxn.unsafe.delay { ctr.getAndIncrement() }.flatMap { retries =>
        if (retries == 3) Rxn.pure("foo")
        else Rxn.unsafe.retry[String]
      }
    }
    def maxRetries(mr: Option[Int]): RetryStrategy.CanSuspend[false] =
      RetryStrategy.Default.withMaxRetries(mr)
    for {
      // finite maxRetries:
      _ <- assertRaisesF(F.delay(never.unsafePerform(this.mcasImpl, maxRetries(Some(4096)))), _.isInstanceOf[Rxn.MaxRetriesExceeded])
      ctr <- F.delay(new AtomicInteger)
      _ <- assertRaisesF(F.delay(countTries(ctr).unsafePerform(this.mcasImpl, maxRetries(Some(42)))), _.isInstanceOf[Rxn.MaxRetriesExceeded])
      _ <- assertResultF(F.delay(ctr.get()), 42 + 1)
      _ <- assertRaisesF(F.delay(succeedsOn3rdRetry(new AtomicInteger).unsafePerform(this.mcasImpl, maxRetries(Some(0)))), _.isInstanceOf[Rxn.MaxRetriesExceeded])
      _ <- assertRaisesF(F.delay(succeedsOn3rdRetry(new AtomicInteger).unsafePerform(this.mcasImpl, maxRetries(Some(2)))), _.isInstanceOf[Rxn.MaxRetriesExceeded])
      _ <- assertResultF(F.delay(succeedsOn3rdRetry(new AtomicInteger).unsafePerform(this.mcasImpl, maxRetries(Some(3)))), "foo")
      // infinite maxRetries:
      _ <- assertResultF(F.delay(succeedsOn3rdRetry(new AtomicInteger).unsafePerform(this.mcasImpl, maxRetries(None))), "foo")
      _ <- assertResultF(F.delay(Rxn.pure("foo").unsafePerform(this.mcasImpl, maxRetries(None))), "foo")
    } yield ()
  }

  test("Strategy options") {
    val s = RetryStrategy
      .Default
      .withRandomizeSpin(false)
      .withMaxSpin(1024)
      .withMaxRetries(Some(42))
    assertRaisesF(
      Reactive[F].run(never, s),
      _.isInstanceOf[Rxn.MaxRetriesExceeded],
    )
  }

  private[this] val sSpin = RetryStrategy.spin(
    maxRetries = None,
    maxSpin = 512,
    randomizeSpin = true,
  )

  test("Running with Strategy.spin") {
    val r: Rxn[Int] = Rxn.pure(3)
    assertResultF(Reactive[F].run(r, sSpin), 3)
  }

  test("Running with Strategy.spin, but with interpretAsync") {
    // TODO: we should test that running it this way is uncancelable
    val r: Rxn[Int] = Rxn.pure(3)
    assertResultF(r.perform[F, Int](this.runtime, sSpin)(using F), 3)
  }

  private[this] val sCede = RetryStrategy.cede(
    maxRetries = None,
    maxSpin = 512,
    randomizeSpin = true,
    maxCede = 3,
    randomizeCede = true,
  )

  test("Running with Strategy.cede") {
    val r: Rxn[Int] = Rxn.pure(3)
    assertResultF(r.perform[F, Int](this.runtime, sCede)(using F), 3)
  }

  test("Running with Strategy.cede should be cancellable") {
    val r: Rxn[Int] = Rxn.unsafe.retry
    val tsk: F[Int] =
      r.perform[F, Int](this.runtime, sCede)(using F).timeoutTo(0.1.second, F.pure(42))
    assertResultF(tsk, 42)
  }

  private[this] val sSleep = RetryStrategy.sleep(
    maxRetries = None,
    maxSpin = 512,
    randomizeSpin = true,
    maxCede = 3,
    randomizeCede = false,
    maxSleep = 10.millis,
    randomizeSleep = true,
  )

  test("Running with Strategy.sleep") {
    val r: Rxn[Int] = Rxn.pure(3)
    assertResultF(r.perform[F, Int](this.runtime, sSleep)(using F), 3)
  }

  test("Running with Strategy.sleep should be cancellable") {
    val r: Rxn[Int] = Rxn.unsafe.retry
    val tsk: F[Int] =
      r.perform[F, Int](this.runtime, sSleep)(using F).timeoutTo(0.1.second, F.pure(42))
    assertResultF(tsk, 42)
  }

  test("Rxn#perform should be repeatable") {
    val r: Rxn[Int] =
      Rxn.pure(42)
    val tsk =
      r.perform[F, Int](this.runtime, sCede)(using F)
    assertResultF(tsk.replicateA(3), List(42, 42, 42))
  }

  test("Executing a Rxn which doesn't change Refs shouldn't change the global version") {
    val r = Ref("foo").flatMap { ref =>
      Rxn.unsafe.delay { new Exception }.map { ex =>
        (ref, ex)
      }
    }
    for {
      v1 <- F.delay { this.mcasImpl.currentContext().start().validTs }
      re <- r.run[F]
      (ref, _) = re
      v2 <- F.delay { this.mcasImpl.currentContext().start().validTs }
      _ <- assertEqualsF(v2, v1)
      // double-check, that modifying a Ref *does* change the version:
      _ <- ref.update(_.substring(0, 1)).run[F]
      v3 <- F.delay { this.mcasImpl.currentContext().start().validTs }
      _ <- assertNotEqualsF(v3, v2)
    } yield ()
  }

  test("Exception passthrough (unsafePerform)") {
    import RxnSpec.{ MyException, throwingRxns, specialExceptions }
    val ref = Ref(0).unsafePerform(this.mcasImpl)
    val arr = Ref.array(4, initial = 0).unsafePerform(this.mcasImpl)
    val local = Rxn.unsafe.newLocal(0).unsafePerform(this.mcasImpl)
    for (r <- throwingRxns(ref, arr, local)) {
      assert(Either.catchOnly[MyException] {
        r.unsafePerform(this.mcasImpl)
      }.isLeft)
    }
    for ((r, cls) <- specialExceptions(ref, arr)) {
      assert(Either.catchOnly[Throwable] {
        r.unsafePerform(this.mcasImpl)
      } (scala.reflect.ClassTag(cls), implicitly).isLeft)
    }
  }

  test("Exception passthrough (Reactive)") {
    import RxnSpec.{ MyException, throwingRxns, specialExceptions }
    (Ref(0) * Ref.array(4, 0) * Rxn.unsafe.newLocal(0)).run[F].flatMap { case ((ref, arr), local) =>
      throwingRxns(ref, arr, local).traverse_ { r =>
        r.run[F].attemptNarrow[MyException].flatMap(e => assertF(e.isLeft))
      }.flatMap { _ =>
        specialExceptions(ref, arr).traverse_ { case (r, cls) =>
          r.run[F].attemptNarrow[Throwable](
            implicitly,
            scala.reflect.ClassTag(cls),
            implicitly,
          ).flatMap(e => assertF(e.isLeft))
        }
      }
    }
  }

  test("Exception passthrough (imperative API: atomically)") {
    import RxnSpec.MyException
    import unsafe.{ newRef, newRefArray, updateRef, updateRefArray, panic, UnsafeApi }
    val uapi = UnsafeApi(this.runtime)
    import uapi.atomically
    val ref = atomically(newRef(0)(using _))
    val arr = atomically(newRefArray(4, initial = 0)(using _))

    assert(Either.catchOnly[MyException] { atomically { implicit ir =>
      updateRef(ref) { _ => throw new MyException(1) }
    } }.isLeft)
    assert(Either.catchOnly[MyException] { atomically { implicit ir =>
      updateRefArray(arr, 0) { _ => throw new MyException(2) }
    } }.isLeft)
    assert(Either.catchOnly[MyException] { atomically { implicit ir =>
      panic(new MyException(3))
    } }.isLeft)
  }

  test("Exception passthrough (imperative API: atomicallyInAsync)") {
    import RxnSpec.MyException
    import unsafe.{ newRef, newRefArray, updateRef, updateRefArray, panic, UnsafeApi }
    val uapi = UnsafeApi(this.runtime)
    import uapi.{ atomically, atomicallyInAsync }
    val ref = atomically(newRef(0)(using _))
    val arr = atomically(newRefArray(4, initial = 0)(using _))

    atomicallyInAsync(RetryStrategy.DefaultSleep) { implicit ir =>
      updateRef(ref) { _ => throw new MyException(1) }
    }.attemptNarrow[MyException].flatMap(e => assertF(e.isLeft))
    atomicallyInAsync(RetryStrategy.DefaultSleep) { implicit ir =>
      updateRefArray(arr, 0) { _ => throw new MyException(2) }
    }.attemptNarrow[MyException].flatMap(e => assertF(e.isLeft))
    atomicallyInAsync(RetryStrategy.DefaultSleep) { implicit ir =>
      panic(new MyException(3)) : String
    }.attemptNarrow[MyException].flatMap(e => assertF(e.isLeft))
  }

  test("Exception in post-commit actions") {
    import RxnSpec.{ MyException, throwingRxns, specialExceptions }
    (Ref(0) * Ref.array(4, 0) * Rxn.unsafe.newLocal(0)).run[F].flatMap { case ((ref, arr), local) =>

      def checkWithPc(throwingRxn: Rxn[Any], cls: Class[_ <: Throwable]) = {
        for {
          ref <- Ref(0).run[F]
          pcRef <- Ref(0).run[F]
          res <- ref.update(_ + 1).as("foo").postCommit { _ =>
            pcRef.update(_ + 1) *> throwingRxn.void
          }.postCommit { _ =>
            pcRef.update(_ + 1)
          }.postCommit { _ =>
            pcRef.update(_ + 1) *> RxnSpec.throwMyException2
          }.run[F].attempt
          _ <- assertResultF(ref.get.run, 1)
          _ <- res match {
            case Left(ex: Rxn.PostCommitException) =>
              (ex.errors match {
                case NonEmptyList(exc0, (exc1) :: Nil) =>
                  assertF(cls.isInstance(exc0), s"unexpected (0): ${exc0}") *> (
                    assertF(exc1.isInstanceOf[RxnSpec.MyException2], s"unexpected (1): ${exc1}")
                  )
                case errors =>
                  failF(s"unexpected errors: ${errors}")
              }) *> assertEqualsF(ex.committedResult, "foo")
            case Left(ex) =>
              F.raiseError(ex)
            case Right(r) =>
              failF(s"unexpected result: ${r}")
          }
          _ <- assertResultF(pcRef.get.run, 1)
        } yield ()
      }

      throwingRxns(ref, arr, local).traverse_ { throwingRxn =>
        checkWithPc(throwingRxn, classOf[MyException])
      }.flatMap { _ =>
        specialExceptions(ref, arr).traverse_ { case (throwingRxn, cls) =>
          checkWithPc(throwingRxn, cls)
        }
      }
    }
  }

  test("Exception in nested post-commit actions") {
    import RxnSpec.{ MyException, throwingRxns, specialExceptions }
    (Ref(0) * Ref.array(4, 0) * Rxn.unsafe.newLocal(0)).run[F].flatMap { case ((ref, arr), local) =>

      def checkWithNestedPc(throwingRxn: Rxn[Any], cls: Class[_ <: Throwable]) = {
        for {
          ref <- Ref(0).run[F]
          pcRef0 <- Ref(0).run[F]
          pcRef1 <- Ref(0).run[F]
          pcRef2 <- Ref(0).run[F]
          res <- ref.update(_ + 1).as("bar").postCommit { _ =>
            pcRef0.update(_ + 1).postCommit { _ =>
              pcRef1.update(_ + 1) *> throwingRxn.void
            }.postCommit { _ =>
              pcRef2.update(_ + 1)
            }
          }.run[F].attempt
          _ <- assertResultF(ref.get.run, 1)
          _ <- res match {
            case Left(ex: Rxn.PostCommitException) =>
              (ex.errors match {
                case NonEmptyList(exc0, Nil) =>
                  assertF(cls.isInstance(exc0), s"unexpected: ${exc0}")
                case errors =>
                  failF(s"unexpected errors: ${errors}")
              }) *> assertEqualsF(ex.committedResult, "bar")
            case Left(ex) =>
              F.raiseError(ex)
            case Right(r) =>
              failF(s"unexpected result: ${r}")
          }
          _ <- assertResultF(pcRef0.get.run, 1)
          _ <- assertResultF(pcRef1.get.run, 0)
          _ <- assertResultF(pcRef2.get.run, 1)
        } yield ()
      }

      throwingRxns(ref, arr, local).traverse_ { throwingRxn =>
        checkWithNestedPc(throwingRxn, classOf[MyException])
      }.flatMap { _ =>
        specialExceptions(ref, arr).traverse_ { case (throwingRxn, cls) =>
          checkWithNestedPc(throwingRxn, cls)
        }
      }
    }
  }
}

private[choam] object RxnSpec {

  private[choam] final class MyException(id: Int) extends Exception(id.toString) {
    def this() = this(0)
    final override def toString: String = s"MyException(${id})"
  }

  private final class MyException2(id: Int) extends Exception(id.toString) {
    final override def toString: String = s"MyException2(${id})"
  }

  private val throwMyException2: Rxn[Unit] =
    Rxn.pure(42).map(_ => throw new MyException2(99))

  private[choam] def throwingRxns(
    ref: Ref[Int],
    arr: Ref.Array[Int],
    leakedLocal: unsafe.RxnLocal[Int],
  ) = List[Rxn[Any]](
    Rxn.pure(42).map(_ => throw new MyException(0)),
    Rxn.pure(43).flatMap(_ => throw new MyException(1)),
    Rxn.pure(44).flatMap[Unit](_ => throw new MyException(2)),
    Rxn.pure(45).flatTap(_ => throw new MyException(3)),
    Rxn.unsafe.delay[Unit] { throw new MyException(4) },
    Rxn.tailRecM(42) { _ => throw new MyException(5) },
    Rxn.tailRecM(0) { i =>
      if (i == 0) Rxn.pure(Left(1))
      else throw new MyException(6)
    },
    Rxn.pure(45).map2[Int, Int](Rxn.pure(46)) { (_, _) =>
      throw new MyException(7)
    },
    Rxn.pure(46).postCommit { _ => throw new MyException(8) },
    Rxn.unsafe.delayContext { _ => throw new MyException(9) },
    Rxn.unsafe.delayContext2 { (_, _) => throw new MyException(10) },
    Rxn.unsafe.embedUnsafe { _ => throw new MyException(11) },
    // Ref (TODO):
    // ref.modify(_ => throw new MyException(12)),
    // ref.update(_ => throw new MyException(13)),
    // ref.tryUpdate(_ => throw new MyException(14)),
    // ref.getAndUpdate(_ => throw new MyException(15)),
    // ref.updateAndGet(_ => throw new MyException(16)),
    // ref.tryModify(_ => throw new MyException(17)),
    // ref.flatModify(_ => throw new MyException(18)),
    Rxn.unsafe.suspend { throw new MyException(19) },
    Rxn.unsafe.suspendContext(_ => throw new MyException(20)),
    Rxn.unsafe.panic(new MyException(21)),
    // imperative API:
    Rxn.unsafe.embedUnsafe { implicit ir =>
      unsafe.updateRef(ref) { _ => throw new MyException(22) }
    },
    Rxn.unsafe.embedUnsafe { implicit ir =>
      unsafe.updateRefArray(arr, 0) { _ => throw new MyException(23) }
    },
    Rxn.unsafe.embedUnsafe { implicit ir =>
      unsafe.panic(new MyException(24))
    },
    // RxnLocal:
    Rxn.unsafe.newLocal(0).flatMap(_.update(_ => throw new MyException(25))),
    Rxn.unsafe.newLocal(0).flatMap(_.getAndUpdate(_ => throw new MyException(26))),
    leakedLocal.update(_ => throw new MyException(27)),
    leakedLocal.getAndUpdate(_ => throw new MyException(28)),
    // Typeclass instances:
    cats.Defer[Rxn].defer { throw new MyException(29) },
    // Ref.Array (TODO):
    // arr.unsafeUpdate(0)(_ => throw new MyException(30)),
    // arr.unsafeModify(0)(_ => throw new MyException(31)),
    // arr.update(0)(_ => throw new MyException(32)),
    // arr.modify(0)(_ => throw new MyException(33)),
    // arr.unsafeFlatModify(0)(_ => throw new MyException(34)),
    // arr.unsafeGetAndUpdate(0)(_ => throw new MyException(35)),
    // arr.refs(0).modify(_ => throw new MyException(36)),
    // arr.refs(0).update(_ => throw new MyException(37)),
    // arr.refs(0).tryUpdate(_ => throw new MyException(38)),
    // arr.refs(0).getAndUpdate(_ => throw new MyException(39)),
    // arr.refs(0).updateAndGet(_ => throw new MyException(40)),
    // arr.refs(0).tryModify(_ => throw new MyException(41)),
    // arr.refs(0).flatModify(_ => throw new MyException(42)),
  )

  private def specialExceptions(ref: Ref[Int], arr: Ref.Array[Int]) = List[(Rxn[Any], Class[_ <: Throwable])](
    (ref.update(_ + 1) *> Rxn.unsafe.unread(ref), classOf[internal.mcas.Hamt.IllegalRemovalException]),
    (Rxn.unsafe.assert(false, "foo"), classOf[AssertionError]),
    (Rxn.unsafe.ticketRead(ref).flatMap { ticket =>
      ref.update(_ + 1) *> ticket.unsafeSet(99)
    }, classOf[internal.mcas.LogEntry.TicketInvalidException]),
    (Rxn.unsafe.ticketReadArray(arr, 0).flatMap { ticket =>
      arr.unsafeUpdate(0)(_ + 1) *> ticket.unsafeSet(99)
    }, classOf[internal.mcas.LogEntry.TicketInvalidException]),
    (Rxn.unsafe.embedUnsafe { implicit ir =>
      val ticket = unsafe.ticketRead(ref)
      unsafe.writeRef(ref, 42)
      ticket.value = 99
    }, classOf[internal.mcas.LogEntry.TicketInvalidException]),
    (Rxn.unsafe.embedUnsafe { implicit ir =>
      val ticket = unsafe.ticketReadArray(arr, 0)
      unsafe.writeRefArray(arr, 0, 42)
      ticket.value = 99
    }, classOf[internal.mcas.LogEntry.TicketInvalidException]),
  )
}
