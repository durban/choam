/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicInteger

import cats.{ Applicative, Monad, StackSafeMonad, Align }
import cats.arrow.{ ArrowChoice, FunctionK }
import cats.data.Ior
import cats.implicits._
import cats.effect.IO
import cats.effect.kernel.{ Ref => CatsRef }
import cats.mtl.Local

import internal.mcas.Mcas

final class RxnSpec_ThreadConfinedMcas_IO
  extends BaseSpecIO
  with SpecThreadConfinedMcas
  with RxnSpec[IO]

trait RxnSpec[F[_]] extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  import Rxn._

  test("Check MCAS implementation") {
    assertSameInstance(Reactive[F].mcasImpl, this.mcasImpl) // just to be sure
  }

  test("Choice should prefer the first option") {
    for {
      r1 <- Ref("r1").run[F]
      r2 <- Ref("r2").run[F]
      rea = r1.unsafeCas("r1", "x") + r2.unsafeCas("r2", "x")
      _ <- assertResultF(rea.run, ())
      _ <- assertResultF(r1.unsafeDirectRead.run, "x")
      _ <- assertResultF(r2.unsafeDirectRead.run, "r2")
    } yield ()
  }

  test("Choice should use the second option, if the first is not available") {
    for {
      r1 <- Ref("z").run[F]
      r2 <- Ref("r2").run[F]
      rea = r1.unsafeCas("r1", "x") + (r2.unsafeCas("r2", "x") * r1.unsafeCas("z", "r1"))
      // r2: "r2" -> "x" AND r1: "z" -> "r1"
      _ <- rea.run
      _ <- assertResultF(r2.unsafeDirectRead.run, "x")
      _ <- assertResultF(r1.unsafeDirectRead.run, "r1")
      // r1: "r1" -> "x"
      _ <- rea.run
      _ <- assertResultF(r1.unsafeDirectRead.run, "x")
      _ <- assertResultF(r2.unsafeDirectRead.run, "x")
    } yield ()
  }

  test("Inner choice should be used first") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("a").run[F]
      rea1 = (r1.unsafeCas("-", "b") + r1.unsafeCas("a", "b")) + r1.unsafeCas("a", "c")
      rea2 = r2.unsafeCas("-", "b") + (r2.unsafeCas("a", "b") + r2.unsafeCas("a", "c"))
      _ <- rea1.run[F]
      _ <- rea2.run[F]
      _ <- assertResultF(r1.unsafeDirectRead.run, "b")
      _ <- assertResultF(r2.unsafeDirectRead.run, "b")
    } yield ()
  }

  test("Choice after >>>") {
    for {
      a <- Ref("a").run[F]
      b <- Ref("b").run[F]
      y <- Ref("y").run[F]
      p <- Ref("p").run[F]
      q <- Ref("q").run[F]
      rea = (
        (
          (Rxn.unsafe.cas(a, "a", "aa") + (Rxn.unsafe.cas(b, "b", "bb") >>> Rxn.unsafe.delay { _ =>
            this.mcasImpl.currentContext().tryPerformSingleCas(y.loc, "y", "-")
          })) >>> Rxn.unsafe.cas(y, "-", "yy")
        ) +
        (Rxn.unsafe.cas(p, "p", "pp") >>> Rxn.unsafe.cas(q, "q", "qq"))
      )
      _ <- assertResultF(F.delay { rea.unsafePerform((), this.mcasImpl) }, ())
      _ <- assertResultF(a.unsafeDirectRead.run, "a")
      _ <- assertResultF(b.unsafeDirectRead.run, "bb")
      _ <- assertResultF(y.unsafeDirectRead.run, "yy")
      _ <- assertResultF(p.unsafeDirectRead.run, "p")
      _ <- assertResultF(q.unsafeDirectRead.run, "q")
    } yield ()
  }

  test("Multiple writes (also in choice)") {
    for {
      a <- Ref("a").run[F]
      p <- Ref("p").run[F]
      rea = a.update(_ => "b") >>> (
        (a.getAndUpdate(_ => "c") >>> p.getAndSet >>> Rxn.unsafe.retry) +
        (a.getAndUpdate(_ => "x") >>> p.getAndSet)
      )
      _ <- assertResultF(F.delay { rea.unsafePerform((), this.mcasImpl) }, "p")
      _ <- assertResultF(a.unsafeDirectRead.run, "x")
      _ <- assertResultF(p.unsafeDirectRead.run, "b")
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
      rea = (Rxn.postCommit(pc1.update(_ + "pc1")) *> r0.update(_ + "b")) >>> (
        (Rxn.postCommit(pc2.update(_ + "pc2")).postCommit(pc4.update(_ + "-")) *> r1.unsafeCas("-", "b")) + ( // <- this will fail
          Rxn.postCommit(pc3.update(_ + "pc3")).postCommit(pc4.update(_ + "pc4")) *> r1.unsafeCas("a", "c")
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
        r1a.unsafeCas("1a", "xa") >>>
        r1b.unsafeCas("1b", "xb") >>>
        (
        (r2a.unsafeCas("2a", "ya") >>> r2b.unsafeCas("2b", "yb")) +
        (r3a.unsafeCas("3a", "za") >>> r3b.unsafeCas("3b", "zb"))
        )
      }
      // 1st choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.unsafeDirectRead.run, "xa")
      _ <- assertResultF(r1b.unsafeDirectRead.run, "xb")
      _ <- assertResultF(r2a.unsafeDirectRead.run, "ya")
      _ <- assertResultF(r2b.unsafeDirectRead.run, "yb")
      _ <- assertResultF(r3a.unsafeDirectRead.run, "3a")
      _ <- assertResultF(r3b.unsafeDirectRead.run, "3b")
      _ <- r1a.unsafeCas("xa", "1a").run
      _ <- r1b.unsafeCas("xb", "1b").run
      _ <- assertResultF(r1a.unsafeDirectRead.run, "1a")
      _ <- assertResultF(r1b.unsafeDirectRead.run, "1b")
      // 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.unsafeDirectRead.run, "xa")
      _ <- assertResultF(r1b.unsafeDirectRead.run, "xb")
      _ <- assertResultF(r2a.unsafeDirectRead.run, "ya")
      _ <- assertResultF(r2b.unsafeDirectRead.run, "yb")
      _ <- assertResultF(r3a.unsafeDirectRead.run, "za")
      _ <- assertResultF(r3b.unsafeDirectRead.run, "zb")
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
        r1a.unsafeDirectRead >>>
        Rxn.computed { s =>
          if (s eq "1a") {
            r1b.unsafeCas("1b", "xb") >>> (r2a.unsafeCas("2a", "ya") + r3a.unsafeCas("3a", "za"))
          } else {
            r1b.unsafeCas("1b", "xx") >>> (r2b.unsafeCas("2b", "yb") + r3b.unsafeCas("3b", "zb"))
          }
        }
      }

      // THEN selected, 1st choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.unsafeDirectRead.run, "1a")
      _ <- assertResultF(r1b.unsafeDirectRead.run, "xb")
      _ <- assertResultF(r2a.unsafeDirectRead.run, "ya")
      _ <- assertResultF(r2b.unsafeDirectRead.run, "2b")
      _ <- assertResultF(r3a.unsafeDirectRead.run, "3a")
      _ <- assertResultF(r3b.unsafeDirectRead.run, "3b")

      _ <- r1b.unsafeCas("xb", "1b").run

      // THEN selected, 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.unsafeDirectRead.run, "1a")
      _ <- assertResultF(r1b.unsafeDirectRead.run, "xb")
      _ <- assertResultF(r2a.unsafeDirectRead.run, "ya")
      _ <- assertResultF(r2b.unsafeDirectRead.run, "2b")
      _ <- assertResultF(r3a.unsafeDirectRead.run, "za")
      _ <- assertResultF(r3b.unsafeDirectRead.run, "3b")

      _ <- r1a.unsafeCas("1a", "xa").run
      _ <- r1b.unsafeCas("xb", "1b").run

      // ELSE selected, 1st choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.unsafeDirectRead.run, "xa")
      _ <- assertResultF(r1b.unsafeDirectRead.run, "xx")
      _ <- assertResultF(r2a.unsafeDirectRead.run, "ya")
      _ <- assertResultF(r2b.unsafeDirectRead.run, "yb")
      _ <- assertResultF(r3a.unsafeDirectRead.run, "za")
      _ <- assertResultF(r3b.unsafeDirectRead.run, "3b")

      _ <- r1b.unsafeCas("xx", "1b").run

      // ELSE selected, 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.unsafeDirectRead.run, "xa")
      _ <- assertResultF(r1b.unsafeDirectRead.run, "xx")
      _ <- assertResultF(r2a.unsafeDirectRead.run, "ya")
      _ <- assertResultF(r2b.unsafeDirectRead.run, "yb")
      _ <- assertResultF(r3a.unsafeDirectRead.run, "za")
      _ <- assertResultF(r3b.unsafeDirectRead.run, "zb")
    } yield ()
  }

  test("Choice should be stack-safe (even when deeply nested)") {
    val n = 16 * 1024
    for {
      ref <- Ref("foo").run[F]
      successfulCas = ref.unsafeCas("foo", "bar")
      fails = (1 to n).foldLeft[Axn[Unit]](Rxn.unsafe.retry) { (r, _) =>
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
      successfulCas = ref.unsafeCas("foo", "bar")
      refs <- (1 to n).toList.traverse { _ =>
        Ref("x").run[F]
      }
      fails = refs.foldLeft[Axn[Unit]](Rxn.unsafe.retry) { (r, ref) =>
        r + ref.unsafeCas("y", "this will never happen")
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
    left = ok1 >>> ((ok2 >>> (failRef.unsafeCas("x_fail", "y_fail") + Rxn.unsafe.retry)) + okRef3.unsafeCas("foo3", "bar3"))
    right = okRef4.unsafeCas("foo4", "bar4")
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

    def oneChoice(leftCont: Axn[Unit], rightCont: Axn[Unit], x: Int, label: String): F[(Axn[Unit], F[Unit])] = for {
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
    } yield (((left >>> leftCont) + (right >>> rightCont)).void, reset)

    for {
      leafs <- (0 until 16).toList.traverse(idx => Ref(s"foo-${idx}").run[F])
      lr1 <- leafs.grouped(2).toList.traverse[F, (Axn[Unit], F[Unit])] {
        case List(refLeft, refRight) =>
          refLeft.unsafeDirectRead.run[F].flatMap { ol =>
            refRight.unsafeDirectRead.run[F].flatMap { or =>
              oneChoice(refLeft.unsafeCas(ol, s"${ol}-new"), refRight.unsafeCas(or, s"${or}-new"), x, "l1")
            }
          }
        case _ =>
          failF()
      }.map(_.toList.unzip)
      (l1, rss1) = lr1
      _ <- assertEqualsF(l1.size, 8)

      lr2 <- l1.grouped(2).toList.traverse[F, (Axn[Unit], F[Unit])] {
        case List(rl, rr) =>
          oneChoice(rl, rr, x, "l2")
        case _ =>
          failF()
      }.map(_.toList.unzip)
      (l2, rss2) = lr2
      _ <- assertEqualsF(l2.size, 4)

      lr3 <- l2.grouped(2).toList.traverse[F, (Axn[Unit], F[Unit])] {
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
          assertResultF(ref.unsafeDirectRead.run[F], expContents)
        }
      }

      _ <- checkLeafs(-1)
      _ <- (0 until leafs.size).toList.traverse { e =>
        top.run[F] >> checkLeafs(e) >> reset
      }
    } yield ()
  }

  def mkOkCASes(n: Int, ov: String, nv: String): F[(List[Ref[String]], Axn[Unit])] = for {
    ref0 <- Ref(ov).run[F]
    refs <- Ref(ov).run[F].replicateA(n - 1)
    r = refs.foldLeft(ref0.unsafeCas(ov, nv)) { (r, ref) =>
      (r * ref.unsafeCas(ov, nv)).void
    }
  } yield (ref0 +: refs, r)

  test("Post-commit actions should be executed") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("").run
      r3 <- Ref("").run
      r = r1.upd[Any, String] { case (s, _) =>
        val r = s + "a"
        (r, r)
      }
      pc1 = r.postCommit(r2.upd[String, Unit] { case (_, x) => (x, ()) })
      pc2 = pc1.postCommit(r3.upd[String, Unit] { case (_, x) => (x, ()) })

      _ <- assertResultF(pc1.run[F], "aa")
      _ <- assertResultF(r1.unsafeDirectRead.run[F], "aa")
      _ <- assertResultF(r2.unsafeDirectRead.run[F], "aa")
      _ <- assertResultF(r3.unsafeDirectRead.run[F], "")

      _ <- assertResultF(pc2.run[F], "aaa")
      _ <- assertResultF(r1.unsafeDirectRead.run[F], "aaa")
      _ <- assertResultF(r2.unsafeDirectRead.run[F], "aaa")
      _ <- assertResultF(r3.unsafeDirectRead.run[F], "aaa")
    } yield ()
  }

  test("Order of post-commit actions") {
    for {
      log <- Ref(List.empty[String]).run[F]
      r1 <- Ref("a").run[F]
      r2 <- Ref("b").run[F]
      rxn = (
        (r1.update(_ + "a").postCommit(log.update("a" :: _)) >>> Rxn.unsafe.retry) + (
          r1.update(_ + "b").postCommit(log.update("b" :: _)).postCommit(log.update("b2" :: _))
        ) >>> Rxn.postCommit(log.update("x" :: _)).postCommit(log.update("y" :: _))
      ) * (
        r2.update(_ + "c").postCommit(log.update("z" :: _))
      )
      _ <- rxn.run[F]
      _ <- assertResultF(r1.get.run[F], "ab")
      _ <- assertResultF(r2.get.run[F], "bc")
      _ <- assertResultF(log.get.map(_.reverse).run[F], List("b", "b2", "x", "y", "z"))
    } yield ()
  }

  test("Formerly impossible CAS should not cause a runtime error") {
    for {
      ref <- Ref("a").run[F]
      r = ref.update(_ + "b") >>> ref.updateAndGet(_ + "x")
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
      _ <- ref.upd { (s, p: String) => (s + p, ()) }[F]("bar")
      res <- ref.get.run[F]
    } yield res

    for {
      _ <- assertResultF(act, "foobar")
      _ <- assertResultF(act, "foobar")
    } yield ()
  }

  test("Rxn.consistentRead") {
    for {
      r1 <- Ref("abc").run[F]
      r2 <- Ref(42).run[F]
      res <- Rxn.consistentRead(r1, r2).run[F]
      _ <- assertEqualsF(res, ("abc", 42))
    } yield ()
  }

  test("Rxn.consistentReadMany") {
    for {
      r1 <- Ref("abc").run[F]
      r2 <- Ref("def").run[F]
      r3 <- Ref("ghi").run[F]
      r4 <- Ref("-").run[F]
      res <- Rxn.consistentReadMany[String](List(r4, r1, r2, r3)).run[F]
      _ <- assertEqualsF(res, List("-", "abc", "def", "ghi"))
    } yield ()
  }

  test("swap") {
    for {
      r1 <- Ref("abc").run[F]
      r2 <- Ref("def").run[F]
      _ <- Rxn.swap(r1, r2).run[F]
      _ <- assertResultF(r1.unsafeDirectRead.run[F], "def")
      _ <- assertResultF(r2.unsafeDirectRead.run[F], "abc")
    } yield ()
  }

  test("flatMap and *>") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("x").run[F]
      _ <- r1.unsafeCas("a", "b").flatMap { _ => r2.unsafeCas("x", "y") }.run[F]
      _ <- (r1.unsafeCas("b", "c") *> r2.unsafeCas("y", "z")).run[F]
      _ <- assertResultF(r1.get.run[F], "c")
      _ <- assertResultF(r2.get.run[F], "z")
    } yield ()
  }

  test("*> and >>") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("x").run[F]
      _ <- (r1.unsafeCas("a", "b") *> r2.unsafeCas("x", "y")).run[F]
      _ <- (r1.unsafeCas("b", "c") >> r2.unsafeCas("y", "z")).run[F]
      _ <- assertResultF(r1.get.run[F], "c")
      _ <- assertResultF(r2.get.run[F], "z")
    } yield ()
  }

  test("*> receives the correct input") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("b").run[F]
      res <- (r1.getAndSet *> r2.getAndSet).apply[F]("x")
      _ <- assertEqualsF(res, "b")
      _ <- assertResultF(r1.get.run[F], "x")
      _ <- assertResultF(r2.get.run[F], "x")
    } yield ()
  }

  test("Recursive >> stack safety") {
    def foo(i: Int, one: Rxn[Int, Int]): Rxn[Int, Int] = {
      if (i == 0) one
      else one >> foo(i - 1, one)
    }
    val rxn = foo(1024 * 1024, Rxn.lift[Int, Int](_ + 1))
    assertResultF(rxn[F](0), 1)
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

  test("Rxn#toFunction") {
    for {
      r <- Ref("a").run[F]
      rxn = r.getAndSet
      f = rxn.toFunction
      v <- f("b").run[F]
      _ <- assertEqualsF(v, "a")
      _ <- assertResultF(r.get.run[F], "b")
    } yield ()
  }

  test("dup") {
    for {
      r <- Ref("a").run[F]
      res <- r.get.dup.run[F]
      _ <- assertEqualsF(res, ("a", "a"))
    } yield ()
  }

  test("dimap") {
    for {
      res <- Rxn.identity[Int].dimap[Int, String](_ * 2)(_.toString).apply[F](4)
      _ <- assertEqualsF(res, "8")
    } yield ()
  }

  test("flatMap and flatMapF") {
    for {
      r <- Ref("x").run[F]
      rxn1 = r.getAndSet.flatMap { x => Rxn.identity[String].map((_, x)) }
      rxn2 = r.getAndSet.flatMapF { x => Rxn.pure(x) }
      _ <- assertResultF(rxn1[F]("X"), ("X", "x"))
      _ <- assertResultF(r.unsafeDirectRead.run[F], "X")
      _ <- assertResultF(rxn2[F]("y"), "X")
      _ <- assertResultF(r.unsafeDirectRead.run[F], "y")
    } yield ()
  }

  test("provide and contramap") {
    for {
      r <- Ref("x").run[F]
      rxn1 = r.getAndSet.provide("a")
      rxn2 = r.getAndSet.contramap[Any](_ => "b")
      _ <- assertResultF(rxn1.run[F], "x")
      _ <- assertResultF(rxn2.run[F], "a")
      _ <- assertResultF(r.unsafeDirectRead.run[F], "b")
    } yield ()
  }

  test("as and map") {
    for {
      r <- Ref("x").run[F]
      rxn1 = r.getAndSet.as("foo")
      rxn2 = r.getAndSet.map(_ => "bar")
      _ <- assertResultF(rxn1[F]("X"), "foo")
      _ <- assertResultF(r.unsafeDirectRead.run[F], "X")
      _ <- assertResultF(rxn2[F]("Y"), "bar")
      _ <- assertResultF(r.unsafeDirectRead.run[F], "Y")
    } yield ()
  }

  test("unsafeCas") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = r.unsafeCas("x", "y")
      _ <- assertResultF(rxn[F](()), ())
      _ <- assertResultF(r.get.run[F], "y")
      _ <- r.getAndSet[F]("a")
      _ <- assertResultF(rxn.attempt[F](0), None)
      _ <- assertResultF(r.get.run[F], "a")
    } yield ()
  }

  test("unsafeCas after read") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = r.get.flatMapF { ov =>
        r.unsafeCas(ov, "y")
      }
      _ <- assertResultF(rxn.run[F], ())
      _ <- assertResultF(r.get.run[F], "y")
      _ <- r.getAndSet[F]("a")
      _ <- assertResultF(rxn.run[F], ())
      _ <- assertResultF(r.get.run[F], "y")
    } yield ()
  }

  test("unsafeCas after write") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = r.getAndSet.provide("y").flatMapF { _ =>
        r.unsafeCas("y", "z")
      }
      _ <- assertResultF(rxn.run[F], ())
      _ <- assertResultF(r.get.run[F], "z")
      _ <- r.getAndSet[F]("a")
      _ <- assertResultF(rxn.run[F], ())
      _ <- assertResultF(r.get.run[F], "z")
    } yield ()
  }

  test("upd") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = r.upd[Int, Boolean] { (s: String, i: Int) =>
        (s.toUpperCase(java.util.Locale.ROOT), i > 0)
      }
      _ <- assertResultF(rxn[F](42), true)
      _ <- assertResultF(r.get.run[F], "X")
      _ <- r.getAndSet[F]("a")
      _ <- assertResultF(rxn[F](0), false)
      _ <- assertResultF(r.get.run[F], "A")
    } yield ()
  }

  test("upd after read") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = r.get.flatMap { ov =>
        r.upd[Int, Boolean] { (s: String, i: Int) =>
          (s.toUpperCase(java.util.Locale.ROOT), (i > 0) && (ov eq "x"))
        }
      }
      _ <- assertResultF(rxn[F](42), true)
      _ <- assertResultF(r.get.run[F], "X")
      _ <- r.getAndSet[F]("a")
      _ <- assertResultF(rxn[F](42), false)
      _ <- assertResultF(r.get.run[F], "A")
    } yield ()
  }

  test("upd after write") {
    for {
      r <- Ref[String]("x").run[F]
      rxn = r.updateAndGet(_ => "y").flatMap { ov =>
        r.upd[Int, Boolean] { (s: String, i: Int) =>
          (s.toUpperCase(java.util.Locale.ROOT), (i > 0) && (ov eq "x"))
        }
      }
      _ <- assertResultF(rxn[F](42), false)
      _ <- assertResultF(r.get.run[F], "Y")
      _ <- r.getAndSet[F]("a")
      _ <- assertResultF(rxn[F](42), false)
      _ <- assertResultF(r.get.run[F], "Y")
    } yield ()
  }

  test("updWith") {
    for {
      r <- Ref[String]("x").run[F]
      c <- Ref[Int](0).run[F]
      rxn = r.updWith[Int, Boolean] { (s: String, i: Int) =>
        c.modify(x => (x + 1, i > 0)).map { b =>
          (s.toUpperCase(java.util.Locale.ROOT), b)
        }
      }
      _ <- assertResultF(rxn[F](42), true)
      _ <- assertResultF(r.get.run[F], "X")
      _ <- assertResultF(c.get.run[F], 1)
      _ <- r.getAndSet[F]("a")
      _ <- assertResultF(rxn[F](0), false)
      _ <- assertResultF(r.get.run[F], "A")
      _ <- assertResultF(c.get.run[F], 2)
    } yield ()
  }

  test("attempt") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("a").run[F]
      r = r1.unsafeCas("x", "y").attempt * r2.unsafeCas("a", "b").?
      _ <- assertResultF(r.run[F], (None, Some(())))
    } yield ()
  }

  test("maybe") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("a").run[F]
      r = r1.unsafeCas("x", "y").maybe * r2.unsafeCas("a", "b").maybe
      _ <- assertResultF(r.run[F], (false, true))
    } yield ()
  }

  test("unsafe.context") {
    Rxn.unsafe.context { (tc: Mcas.ThreadContext) =>
      tc eq this.mcasImpl.currentContext()
    }.run[F].flatMap(ok => assertF(ok))
  }

  test("unsafe.ticketRead") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("a").run[F]
      r = r2.unsafeTicketRead.flatMapF { ticket =>
        r1.getAndUpdate(_ + ticket.unsafePeek).flatMapF { ov =>
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

  test("unsafe.forceValidate (dummy)") {
    for {
      r1 <- Ref("a").run[F]
      _ <- r1.update { _ => "x" }.run[F]
      rxn = r1.get.flatMapF { v1 =>
        Rxn.unsafe.forceValidate.flatMapF { _ =>
          r1.get.map { v2 => (v1, v2) }
        }
      }
      _ <- assertResultF(rxn.run[F], ("x", "x"))
    } yield ()
  }

  // This tests an implementation detail,
  // because we depend on this implementation
  // detail in some of our tests:
  test("unsafe.delay(throw)") {
    final class MyException extends Exception
    val exc = new MyException
    def attemptRun[A](axn: Axn[A]): F[Either[Throwable, A]] = {
      rF.run(axn).attempt
    }
    for {
      _ <- assertResultF(
        attemptRun[Int](Rxn.unsafe.delay { _ => 42 }),
        Right(42),
      )
      _ <- assertResultF(
        attemptRun[Int](Rxn.unsafe.delay { _ => throw exc }),
        Left(exc),
      )
      _ <- assertResultF(
        attemptRun[Int](Rxn.unsafe.delay[Any, Int] { _ => throw exc } >>> Rxn.unsafe.retry),
        Left(exc),
      )
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
      unsafeRxn = ref.unsafeDirectRead.flatMap { v =>
        Rxn.pure(42).flatMap { _ =>
          ref.unsafeCas(ov = v, nv = v + 1)
        }
      }
      res <- F.delay {
        unsafeRxn.?.unsafePerform((), this.mcasImpl)
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
      _ <- ref.getAndSet[F](42)
      _ <- unsafeRxn.run[F]
      _ <- assertResultF(ref.get.run[F], 43)
    } yield ()
  }

  test("Monad instance") {
    def foo[G[_] : StackSafeMonad](ga: G[Int]): G[String] =
      ga.flatMap(x => x.toString.pure[G])

    assertResultF(foo[Rxn[Any, *]](Rxn.pure(42)).run[F], "42")
  }

  test("ArrowChoice instance") {
    def foo[G[_, _] : ArrowChoice](
      ga: G[Int, String],
      gb: G[Int, Int]
    ): G[Int, (String, Int)] = ga &&& gb

    assertResultF(
      foo[Rxn](Rxn.lift(_.toString), Rxn.lift(_ + 1)).apply[F](42),
      ("42", 43)
    )
  }

  test("Local instance") {
    def foo[G[_] : Monad](implicit G: Local[G, Int]): G[String] = for {
      e1 <- G.ask
      e2 <- G.scope(G.ask)(42)
    } yield s"${e1}, ${e2}"

    assertResultF(foo[Rxn[Int, *]].apply[F](21), "21, 42")
  }

  test("Applicative instance") {
    def foo[G[_] : Applicative](ga: G[Int], gb: G[Int]): G[Int] =
      ga.map2(gb)(_ + _)

    assertResultF(foo(Rxn.ret(21), Rxn.ret(21)).run[F], 42)
  }

  test("Align instance") {
    val inst = Align[Rxn[Int, *]]
    for {
      res1 <- inst.align(Rxn.unsafe.retry[Int, Int], Rxn.ret[Long](42L)).apply[F](0)
      _ <- assertEqualsF(res1, Ior.right(42L))
      res2 <- inst.align(Rxn.ret[Int](42), Rxn.unsafe.retry[Int, Long]).apply[F](0)
      _ <- assertEqualsF(res2, Ior.left(42))
      res3 <- inst.align(Rxn.ret[Int](42), Rxn.ret[Long](23L)).apply[F](0)
      _ <- assertEqualsF(res3, Ior.both(42, 23L))
    } yield ()
  }

  test("Ref.Make instance") {
    val inst = implicitly[CatsRef.Make[Rxn[Int, *]]]
    val rxn = inst.refOf(42).flatMap { ref =>
      ref.get
    }
    assertResultF(rxn[F](-1), 42)
  }

  test("UUIDGen instance") {
    val inst = cats.effect.std.UUIDGen[Rxn[Any, *]]
    for {
      u1 <- inst.randomUUID.run[F]
      u2 <- inst.randomUUID.run[F]
      _ <- assertNotEqualsF(u1, u2)
    } yield ()
  }

  test("Clock instance") {
    val inst = cats.effect.kernel.Clock[Axn]
    for {
      t1t2 <- (inst.monotonic * inst.monotonic).run[F]
      _ <- assertF(t1t2._1 <= t1t2._2)
    } yield ()
  }

  test("Tuple2 syntax") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("b").run[F]
      r = r1.get.map2(r2.get)((_, _))
      _ <- assertResultF(r.left.run[F], "a")
      _ <- assertResultF(r.right.run[F], "b")
      rr = r.split(Rxn.identity, Rxn.lift[String, String](_ + "x"))
      _ <- assertResultF(rr.run[F], ("a", "bx"))
    } yield ()
  }

  test("Reactive is a FunctionK") {
    Reactive[F] : FunctionK[Axn, F]
  }

  test("maxRetries") {
    val never = Rxn.unsafe.retry[Any, Int]
    def countTries(ctr: AtomicInteger) = {
      Axn.unsafe.delay { ctr.getAndIncrement() } *> Rxn.unsafe.retry[Any, Int]
    }
    def succeedsOn3rdRetry(ref: Ref[String]) = {
      Rxn.unsafe.retry[Any, String] + ref.unsafeCas("x", "y").as("z") + Rxn.unsafe.retry[Any, String] + Rxn.pure("foo")
    }
    for {
      // finite maxRetries:
      _ <- assertRaisesF(F.delay(never.unsafePerform((), this.mcasImpl, maxRetries = Some(4096))), _.isInstanceOf[Rxn.MaxRetriesReached])
      ctr <- F.delay(new AtomicInteger)
      _ <- assertRaisesF(F.delay(countTries(ctr).unsafePerform((), this.mcasImpl, maxRetries = Some(42))), _.isInstanceOf[Rxn.MaxRetriesReached])
      _ <- assertResultF(F.delay(ctr.get()), 42 + 1)
      r <- Ref("a").run[F]
      _ <- assertRaisesF(F.delay(succeedsOn3rdRetry(r).unsafePerform((), this.mcasImpl, maxRetries = Some(2))), _.isInstanceOf[Rxn.MaxRetriesReached])
      _ <- assertResultF(F.delay(succeedsOn3rdRetry(r).unsafePerform((), this.mcasImpl, maxRetries = Some(3))), "foo")
      // infinite maxRetries:
      _ <- assertResultF(F.delay(succeedsOn3rdRetry(r).unsafePerform((), this.mcasImpl, maxRetries = None)), "foo")
      _ <- assertResultF(F.delay(Rxn.pure("foo").unsafePerform((), this.mcasImpl, maxRetries = None)), "foo")
    } yield ()
  }
}
