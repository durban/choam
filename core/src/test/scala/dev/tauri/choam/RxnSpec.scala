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

import cats.{ Applicative, Monad }
import cats.arrow.ArrowChoice
import cats.implicits._
import cats.effect.IO
import cats.mtl.Local

import kcas._

final class RxnSpec_NaiveKCAS_IO
  extends BaseSpecIO
  with SpecNaiveKCAS
  with RxnSpec[IO]

final class RxnSpec_NaiveKCAS_ZIO
  extends BaseSpecZIO
  with SpecNaiveKCAS
  with RxnSpec[zio.Task]

final class RxnSpec_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with RxnSpec[IO]

final class RxnSpec_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with RxnSpec[zio.Task]

trait RxnSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  import Rxn._

  test("Sanity check") {
    assertSameInstance(Reactive[F].kcasImpl, this.kcasImpl)
    println(s"NUM_CPU = ${Runtime.getRuntime().availableProcessors()}")
  }

  test("Choice should prefer the first option") {
    for {
      r1 <- Ref("r1").run[F]
      r2 <- Ref("r2").run[F]
      rea = r1.unsafeCas("r1", "x") + r2.unsafeCas("r2", "x")
      _ <- assertResultF(rea.run, ())
      _ <- assertResultF(r1.unsafeInvisibleRead.run, "x")
      _ <- assertResultF(r2.unsafeInvisibleRead.run, "r2")
    } yield ()
  }

  test("Choice should use the second option, if the first is not available") {
    for {
      r1 <- Ref("z").run[F]
      r2 <- Ref("r2").run[F]
      rea = r1.unsafeCas("r1", "x") + (r2.unsafeCas("r2", "x") * r1.unsafeCas("z", "r1"))
      // r2: "r2" -> "x" AND r1: "z" -> "r1"
      _ <- rea.run
      _ <- assertResultF(r2.unsafeInvisibleRead.run, "x")
      _ <- assertResultF(r1.unsafeInvisibleRead.run, "r1")
      // r1: "r1" -> "x"
      _ <- rea.run
      _ <- assertResultF(r1.unsafeInvisibleRead.run, "x")
      _ <- assertResultF(r2.unsafeInvisibleRead.run, "x")
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
      _ <- assertResultF(r1.unsafeInvisibleRead.run, "b")
      _ <- assertResultF(r2.unsafeInvisibleRead.run, "b")
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
            this.kcasImpl.doSingleCas(y.loc, "y", "-", this.kcasImpl.currentContext())
          })) >>> Rxn.unsafe.cas(y, "-", "yy")
        ) +
        (Rxn.unsafe.cas(p, "p", "pp") >>> Rxn.unsafe.cas(q, "q", "qq"))
      )
      _ <- assertResultF(F.delay { rea.unsafePerform((), this.kcasImpl) }, ())
      _ <- assertResultF(a.unsafeInvisibleRead.run, "a")
      _ <- assertResultF(b.unsafeInvisibleRead.run, "bb")
      _ <- assertResultF(y.unsafeInvisibleRead.run, "yy")
      _ <- assertResultF(p.unsafeInvisibleRead.run, "p")
      _ <- assertResultF(q.unsafeInvisibleRead.run, "q")
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
      _ <- assertResultF(r1a.unsafeInvisibleRead.run, "xa")
      _ <- assertResultF(r1b.unsafeInvisibleRead.run, "xb")
      _ <- assertResultF(r2a.unsafeInvisibleRead.run, "ya")
      _ <- assertResultF(r2b.unsafeInvisibleRead.run, "yb")
      _ <- assertResultF(r3a.unsafeInvisibleRead.run, "3a")
      _ <- assertResultF(r3b.unsafeInvisibleRead.run, "3b")
      _ <- r1a.unsafeCas("xa", "1a").run
      _ <- r1b.unsafeCas("xb", "1b").run
      _ <- assertResultF(r1a.unsafeInvisibleRead.run, "1a")
      _ <- assertResultF(r1b.unsafeInvisibleRead.run, "1b")
      // 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.unsafeInvisibleRead.run, "xa")
      _ <- assertResultF(r1b.unsafeInvisibleRead.run, "xb")
      _ <- assertResultF(r2a.unsafeInvisibleRead.run, "ya")
      _ <- assertResultF(r2b.unsafeInvisibleRead.run, "yb")
      _ <- assertResultF(r3a.unsafeInvisibleRead.run, "za")
      _ <- assertResultF(r3b.unsafeInvisibleRead.run, "zb")
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
        r1a.unsafeInvisibleRead >>>
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
      _ <- assertResultF(r1a.unsafeInvisibleRead.run, "1a")
      _ <- assertResultF(r1b.unsafeInvisibleRead.run, "xb")
      _ <- assertResultF(r2a.unsafeInvisibleRead.run, "ya")
      _ <- assertResultF(r2b.unsafeInvisibleRead.run, "2b")
      _ <- assertResultF(r3a.unsafeInvisibleRead.run, "3a")
      _ <- assertResultF(r3b.unsafeInvisibleRead.run, "3b")

      _ <- r1b.unsafeCas("xb", "1b").run

      // THEN selected, 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.unsafeInvisibleRead.run, "1a")
      _ <- assertResultF(r1b.unsafeInvisibleRead.run, "xb")
      _ <- assertResultF(r2a.unsafeInvisibleRead.run, "ya")
      _ <- assertResultF(r2b.unsafeInvisibleRead.run, "2b")
      _ <- assertResultF(r3a.unsafeInvisibleRead.run, "za")
      _ <- assertResultF(r3b.unsafeInvisibleRead.run, "3b")

      _ <- r1a.unsafeCas("1a", "xa").run
      _ <- r1b.unsafeCas("xb", "1b").run

      // ELSE selected, 1st choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.unsafeInvisibleRead.run, "xa")
      _ <- assertResultF(r1b.unsafeInvisibleRead.run, "xx")
      _ <- assertResultF(r2a.unsafeInvisibleRead.run, "ya")
      _ <- assertResultF(r2b.unsafeInvisibleRead.run, "yb")
      _ <- assertResultF(r3a.unsafeInvisibleRead.run, "za")
      _ <- assertResultF(r3b.unsafeInvisibleRead.run, "3b")

      _ <- r1b.unsafeCas("xx", "1b").run

      // ELSE selected, 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.unsafeInvisibleRead.run, "xa")
      _ <- assertResultF(r1b.unsafeInvisibleRead.run, "xx")
      _ <- assertResultF(r2a.unsafeInvisibleRead.run, "ya")
      _ <- assertResultF(r2b.unsafeInvisibleRead.run, "yb")
      _ <- assertResultF(r3a.unsafeInvisibleRead.run, "za")
      _ <- assertResultF(r3b.unsafeInvisibleRead.run, "zb")
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
          refLeft.unsafeInvisibleRead.run[F].flatMap { ol =>
            refRight.unsafeInvisibleRead.run[F].flatMap { or =>
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
          assertResultF(ref.unsafeInvisibleRead.run[F], expContents)
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
      r = r1.upd[Unit, String] { case (s, _) =>
        val r = s + "a"
        (r, r)
      }
      pc1 = r.postCommit(r2.upd[String, Unit] { case (_, x) => (x, ()) })
      pc2 = pc1.postCommit(r3.upd[String, Unit] { case (_, x) => (x, ()) })

      _ <- assertResultF(pc1.run[F], "aa")
      _ <- assertResultF(r1.unsafeInvisibleRead.run[F], "aa")
      _ <- assertResultF(r2.unsafeInvisibleRead.run[F], "aa")
      _ <- assertResultF(r3.unsafeInvisibleRead.run[F], "")

      _ <- assertResultF(pc2.run[F], "aaa")
      _ <- assertResultF(r1.unsafeInvisibleRead.run[F], "aaa")
      _ <- assertResultF(r2.unsafeInvisibleRead.run[F], "aaa")
      _ <- assertResultF(r3.unsafeInvisibleRead.run[F], "aaa")
    } yield ()
  }

  test("delayComputed prepare is not part of the reaction") {

    final case class Node(value: Ref[String], next: Ref[Node])

    object Node {

      def newSentinel(): Node =
        Node(Ref.unsafe(null), Ref.unsafe(null))

      def fromList(l: List[String]): Node = l match {
        case Nil =>
          newSentinel()
        case h :: t =>
          Node(value = Ref.unsafe(h), next = Ref.unsafe(fromList(t)))
      }

      def pop(head: Ref[Node]): Axn[String] = Rxn.unsafe.delayComputed {
        head.unsafeInvisibleRead.flatMap { h =>
          h.value.unsafeInvisibleRead.flatMap {
            case null =>
              // sentinel node, discard it and retry:
              h.next.get.flatMap { nxt =>
                head.unsafeCas(h, nxt)
              }.as(Rxn.unsafe.retry)
            case v =>
              // found the real head, pop it:
              Rxn.ret(h.next.get.flatMap { nxt =>
                head.unsafeCas(h, nxt).flatMap { _ =>
                  h.value.unsafeCas(v, v)
                }
              }.as(v))
          }
        }
      }
    }

    for {
      _ <- F.delay { this.assume(this.kcasImpl ne kcas.KCAS.NaiveKCAS) } // TODO: fix with naive k-CAS
      // sanity check:
      lst0 = List[String](null, "a", "b", null, "c")
      lst1 <- F.delay { Ref.unsafe(Node.fromList(lst0)) }
      lst2 <- F.tailRecM((List.empty[String], lst1)) { case (acc, ref) =>
        ref.get.flatMap { node =>
          if (node eq null) {
            // there is an extra sentinel at the end:
            Rxn.ret(Right[(List[String], Ref[Node]), List[String]](acc.tail.reverse))
          } else {
            node.value.get.map { v =>
              Left[(List[String], Ref[Node]), List[String]]((v :: acc, node.next))
            }
          }
        }.run[F]
      }
      _ <- assertEqualsF(lst2, lst0)
      // real test:
      r1 <- F.delay { Ref.unsafe(Node.fromList(List[String](null, "a", "b", null, "x"))) }
      r2 <- F.delay { Ref.unsafe(Node.fromList(List[String](null, "c", null, null, "d", "y"))) }
      popBoth = (Node.pop(r1) * Node.pop(r2)).run[F]
      _ <- assertResultF(popBoth, ("a", "c"))
      _ <- assertResultF(popBoth, ("b", "d"))
      _ <- assertResultF(popBoth, ("x", "y"))
      _ <- assertResultF(r1.unsafeInvisibleRead.flatMap(_.value.unsafeInvisibleRead).run[F], null)
      _ <- assertResultF(r2.unsafeInvisibleRead.flatMap(_.value.unsafeInvisibleRead).run[F], null)
    } yield ()
  }

  test("postCommit on delayComputed") {
    for {
      a <- Ref("a").run[F]
      b <- Ref("b").run[F]
      c <- Ref("c").run[F]
      rea = Rxn.unsafe.delayComputed[Unit, String](Rxn.unsafe.cas(a, "a", "aa").as(Rxn.ret("foo")).postCommit(
        Rxn.unsafe.cas(b, "b", "bb")
      )) >>> Rxn.unsafe.cas(c, "c", "cc")
      _ <- assertResultF(rea.run[F], ())
      _ <- assertResultF(a.unsafeInvisibleRead.run, "aa")
      _ <- assertResultF(b.unsafeInvisibleRead.run, "bb")
      _ <- assertResultF(c.unsafeInvisibleRead.run, "cc")
    } yield ()
  }

  test("delayComputed") {
    for {
      a <- Ref("a").run[F]
      b <- Ref("b").run[F]
      rea = Rxn.unsafe.delayComputed[Unit, String](Rxn.ref.upd(a) { (oa: String, _: Unit) =>
        ("x", oa)
      }.map { oa => Rxn.ref.upd(b) { (ob: String, _: Any) => (oa, ob) } })
      _ <- assertResultF(F.delay { rea.unsafePerform((), this.kcasImpl) }, "b")
      _ <- assertResultF(a.unsafeInvisibleRead.run, "x")
      _ <- assertResultF(b.unsafeInvisibleRead.run, "a")
    } yield ()
  }

  test("Impossible CAS should cause a runtime error") {
    for {
      ref <- Ref("a").run[F]
      r = ref.update(_ + "b") >>> ref.update(_ + "x")
      res <- r.run[F].attempt
      _ <- res match {
        case Left(ex) =>
          assertF(ex.getMessage.contains("Impossible k-CAS")).flatMap { _ =>
            assertF(ex.isInstanceOf[ImpossibleOperation])
          }
        case Right(_) =>
          failF("Unexpected success")
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

  test("attempt") {
    for {
      r1 <- Ref("a").run[F]
      r2 <- Ref("a").run[F]
      r = r1.unsafeCas("x", "y").attempt * r2.unsafeCas("a", "b").?
      _ <- assertResultF(r.run[F], (None, Some(())))
    } yield ()
  }

  test("unsafe.context") {
    Rxn.unsafe.context { (tc: ThreadContext) =>
      tc.impl eq this.kcasImpl
    }.run[F].flatMap(ok => assertF(ok))
  }

  test("Monad instance") {
    def foo[G[_] : Monad](ga: G[Int]): G[String] =
      ga.flatMap(x => x.toString.pure[G])

    assertResultF(foo[Rxn[Any, *]](Rxn.ret(42)).run[F], "42")
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
}
