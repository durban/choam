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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

import scala.jdk.CollectionConverters._

import cats.implicits._
import cats.effect.IO

import kcas._

final class ReactSpecNaiveKCAS
  extends BaseSpecIO
  with SpecNaiveKCAS
  with ReactSpec[IO]

final class ReactSpecEMCAS
  extends BaseSpecIO
  with SpecEMCAS
  with ReactSpec[IO]

trait ReactSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  import React._

  test("Sanity check") {
    assertSameInstance(Reactive[F].kcasImpl, this.kcasImpl)
  }

  test("Simple CAS should work as expected") {
    for {
      ref <- React.newRef("ert").run[F]
      rea = lift((_: Int).toString) × (ref.cas("ert", "xyz") >>> lift(_ => "boo"))
      s12 <- rea((5, ()))
      (s1, s2) = s12
      _ <- assertEqualsF(s1, "5")
      _ <- assertEqualsF(s2, "boo")
      _ <- assertResultF(ref.invisibleRead.run[F], "xyz")
    } yield ()
  }

  test("updWith should behave correctly when used through modifyWith") {
    for {
      r1 <- React.newRef("foo").run[F]
      r2 <- React.newRef("x").run[F]
      r = r1.modifyWith { ov =>
        if (ov eq "foo") React.ret("bar")
        else r2.upd[Unit, String] { (o2, _) => (ov, o2) }
      }
      _ <- r.run
      _ <- assertResultF(r1.invisibleRead.run, "bar")
      _ <- assertResultF(r2.invisibleRead.run, "x")
      _ <- r.run
      _ <- assertResultF(r1.invisibleRead.run, "x")
      _ <- assertResultF(r2.invisibleRead.run, "bar")
    } yield ()
  }

  test("Choice should prefer the first option") {
    for {
      r1 <- React.newRef("r1").run[F]
      r2 <- React.newRef("r2").run[F]
      rea = r1.cas("r1", "x") + r2.cas("r2", "x")
      _ <- assertResultF(rea.run, ())
      _ <- assertResultF(r1.invisibleRead.run, "x")
      _ <- assertResultF(r2.invisibleRead.run, "r2")
    } yield ()
  }

  test("Choice should use the second option, if the first is not available") {
    for {
      r1 <- React.newRef("z").run[F]
      r2 <- React.newRef("r2").run[F]
      rea = r1.cas("r1", "x") + (r2.cas("r2", "x") * r1.cas("z", "r1"))
      // r2: "r2" -> "x" AND r1: "z" -> "r1"
      _ <- rea.run
      _ <- assertResultF(r2.invisibleRead.run, "x")
      _ <- assertResultF(r1.invisibleRead.run, "r1")
      // r1: "r1" -> "x"
      _ <- rea.run
      _ <- assertResultF(r1.invisibleRead.run, "x")
      _ <- assertResultF(r2.invisibleRead.run, "x")
    } yield ()
  }

  test("Choice should work if it's after some other operation") {
    for {
      r1a <- React.newRef("1a").run[F]
      r1b <- React.newRef("1b").run[F]
      r2a <- React.newRef("2a").run[F]
      r2b <- React.newRef("2b").run[F]
      r3a <- React.newRef("3a").run[F]
      r3b <- React.newRef("3b").run[F]
      rea = {
        r1a.cas("1a", "xa") >>>
        r1b.cas("1b", "xb") >>>
        (
        (r2a.cas("2a", "ya") >>> r2b.cas("2b", "yb")) +
        (r3a.cas("3a", "za") >>> r3b.cas("3b", "zb"))
        )
      }
      // 1st choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.invisibleRead.run, "xa")
      _ <- assertResultF(r1b.invisibleRead.run, "xb")
      _ <- assertResultF(r2a.invisibleRead.run, "ya")
      _ <- assertResultF(r2b.invisibleRead.run, "yb")
      _ <- assertResultF(r3a.invisibleRead.run, "3a")
      _ <- assertResultF(r3b.invisibleRead.run, "3b")
      _ <- r1a.cas("xa", "1a").run
      _ <- r1b.cas("xb", "1b").run
      _ <- assertResultF(r1a.invisibleRead.run, "1a")
      _ <- assertResultF(r1b.invisibleRead.run, "1b")
      // 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.invisibleRead.run, "xa")
      _ <- assertResultF(r1b.invisibleRead.run, "xb")
      _ <- assertResultF(r2a.invisibleRead.run, "ya")
      _ <- assertResultF(r2b.invisibleRead.run, "yb")
      _ <- assertResultF(r3a.invisibleRead.run, "za")
      _ <- assertResultF(r3b.invisibleRead.run, "zb")
    } yield ()
  }

  test("Choice should work even if it's computed") {
    for {
      r1a <- React.newRef("1a").run[F]
      r1b <- React.newRef("1b").run[F]
      r2a <- React.newRef("2a").run[F]
      r2b <- React.newRef("2b").run[F]
      r3a <- React.newRef("3a").run[F]
      r3b <- React.newRef("3b").run[F]
      rea = {
        r1a.invisibleRead >>>
        React.computed { s =>
          if (s eq "1a") {
            r1b.cas("1b", "xb") >>> (r2a.cas("2a", "ya") + r3a.cas("3a", "za"))
          } else {
            r1b.cas("1b", "xx") >>> (r2b.cas("2b", "yb") + r3b.cas("3b", "zb"))
          }
        }
      }

      // THEN selected, 1st choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.invisibleRead.run, "1a")
      _ <- assertResultF(r1b.invisibleRead.run, "xb")
      _ <- assertResultF(r2a.invisibleRead.run, "ya")
      _ <- assertResultF(r2b.invisibleRead.run, "2b")
      _ <- assertResultF(r3a.invisibleRead.run, "3a")
      _ <- assertResultF(r3b.invisibleRead.run, "3b")

      _ <- r1b.cas("xb", "1b").run

      // THEN selected, 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.invisibleRead.run, "1a")
      _ <- assertResultF(r1b.invisibleRead.run, "xb")
      _ <- assertResultF(r2a.invisibleRead.run, "ya")
      _ <- assertResultF(r2b.invisibleRead.run, "2b")
      _ <- assertResultF(r3a.invisibleRead.run, "za")
      _ <- assertResultF(r3b.invisibleRead.run, "3b")

      _ <- r1a.cas("1a", "xa").run
      _ <- r1b.cas("xb", "1b").run

      // ELSE selected, 1st choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.invisibleRead.run, "xa")
      _ <- assertResultF(r1b.invisibleRead.run, "xx")
      _ <- assertResultF(r2a.invisibleRead.run, "ya")
      _ <- assertResultF(r2b.invisibleRead.run, "yb")
      _ <- assertResultF(r3a.invisibleRead.run, "za")
      _ <- assertResultF(r3b.invisibleRead.run, "3b")

      _ <- r1b.cas("xx", "1b").run

      // ELSE selected, 2nd choice selected:
      _ <- rea.run
      _ <- assertResultF(r1a.invisibleRead.run, "xa")
      _ <- assertResultF(r1b.invisibleRead.run, "xx")
      _ <- assertResultF(r2a.invisibleRead.run, "ya")
      _ <- assertResultF(r2b.invisibleRead.run, "yb")
      _ <- assertResultF(r3a.invisibleRead.run, "za")
      _ <- assertResultF(r3b.invisibleRead.run, "zb")
    } yield ()
  }

  test("Choice should be stack-safe (even when deeply nested)") {
    val n = 16 * React.maxStackDepth
    for {
      ref <- React.newRef("foo").run[F]
      successfulCas = ref.cas("foo", "bar")
      fails = (1 to n).foldLeft[React[Unit, Unit]](React.retry) { (r, _) =>
        r + React.retry
      }
      r = fails + successfulCas
      _ <- assertResultF(r.run, ())
      _ <- assertResultF(ref.getter.run, "bar")
    } yield ()
  }

  test("Choice should be stack-safe (even when deeply nested and doing actual CAS-es)") {
    val n = 16 * React.maxStackDepth
    for {
      ref <- React.newRef("foo").run[F]
      successfulCas = ref.cas("foo", "bar")
      refs <- (1 to n).toList.traverse { _ =>
        React.newRef("x").run[F]
      }
      fails = refs.foldLeft[React[Unit, Unit]](React.retry) { (r, ref) =>
        r + ref.cas("y", "this will never happen")
      }
      r = fails + successfulCas
      _ <- assertResultF(r.run[F], ())
      _ <- assertResultF(ref.getter.run[F], "bar")
      _ <- refs.traverse { ref =>
        assertResultF(ref.getter.run[F], "x")
      }
    } yield ()
  }

  test("Choice should correctly backtrack (1) (no jumps)") {
    backtrackTest1(2)
  }

  test("Choice should correctly backtrack (1) (even with jumps)") {
    backtrackTest1(React.maxStackDepth + 1)
  }

  test("Choice should correctly backtrack (2) (no jumps)") {
    backtrackTest2(2)
  }

  test("Choice should correctly backtrack (2) (even with jumps)") {
    backtrackTest2(React.maxStackDepth / 4)
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
    okRef3 <- React.newRef("foo3").run
    okRef4 <- React.newRef("foo4").run
    failRef <- React.newRef("fail").run
    left = ok1 >>> ((ok2 >>> (failRef.cas("x_fail", "y_fail") + React.retry)) + okRef3.cas("foo3", "bar3"))
    right = okRef4.cas("foo4", "bar4")
    r = left + right
    _ <- assertResultF(r.run[F], ())
    _ <- okRefs1.traverse { ref =>
      assertResultF(ref.getter.run, "bar1")
    }
    _ <- okRefs2.traverse { ref =>
      assertResultF(ref.getter.run[F], "foo2")
    }
    _ <- assertResultF(okRef3.getter.run[F], "bar3")
    _ <- assertResultF(okRef4.getter.run[F], "foo4")
    _ <- assertResultF(failRef.getter.run[F], "fail")
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

    def oneChoice(leftCont: React[Unit, Unit], rightCont: React[Unit, Unit], x: Int, label: String): F[(React[Unit, Unit], F[Unit])] = for {
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
        lRefs.traverse { ref => ref.modify(_ => ol).run[F] }.flatMap { _ =>
          rRefs.traverse { ref => ref.modify(_ => or).run[F] }
        }.void
      }
    } yield (((left >>> leftCont) + (right >>> rightCont)).discard, reset)

    for {
      leafs <- (0 until 16).toList.traverse(idx => React.newRef(s"foo-${idx}").run[F])
      lr1 <- leafs.grouped(2).toList.traverse[F, (React[Unit, Unit], F[Unit])] {
        case List(refLeft, refRight) =>
          refLeft.invisibleRead.run[F].flatMap { ol =>
            refRight.invisibleRead.run[F].flatMap { or =>
              oneChoice(refLeft.cas(ol, s"${ol}-new"), refRight.cas(or, s"${or}-new"), x, "l1")
            }
          }
        case _ =>
          failF()
      }.map(_.toList.unzip)
      (l1, rss1) = lr1
      _ <- assertEqualsF(l1.size, 8)

      lr2 <- l1.grouped(2).toList.traverse[F, (React[Unit, Unit], F[Unit])] {
        case List(rl, rr) =>
          oneChoice(rl, rr, x, "l2")
        case _ =>
          failF()
      }.map(_.toList.unzip)
      (l2, rss2) = lr2
      _ <- assertEqualsF(l2.size, 4)

      lr3 <- l2.grouped(2).toList.traverse[F, (React[Unit, Unit], F[Unit])] {
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
          assertResultF(ref.invisibleRead.run[F], expContents)
        }
      }

      _ <- checkLeafs(-1)
      _ <- (0 until leafs.size).toList.traverse { e =>
        top.run[F] >> checkLeafs(e) >> reset
      }
    } yield ()
  }

  def mkOkCASes(n: Int, ov: String, nv: String): F[(List[Ref[String]], React[Unit, Unit])] = for {
    ref0 <- React.newRef(ov).run[F]
    refs <- React.newRef(ov).run[F].replicateA(n - 1)
    r = refs.foldLeft(ref0.cas(ov, nv)) { (r, ref) =>
      (r * ref.cas(ov, nv)).discard
    }
  } yield (ref0 +: refs, r)

  test("Post-commit actions should be executed") {
    for {
      r1 <- React.newRef("x").run[F]
      r2 <- React.newRef("").run
      r3 <- React.newRef("").run
      r = r1.upd[Unit, String] { case (s, _) =>
        val r = s + "x"
        (r, r)
      }
      pc1 = r.postCommit(r2.upd[String, Unit] { case (_, x) => (x, ()) })
      pc2 = pc1.postCommit(r3.upd[String, Unit] { case (_, x) => (x, ()) })

      _ <- assertResultF(pc1.run[F], "xx")
      _ <- assertResultF(r1.invisibleRead.run[F], "xx")
      _ <- assertResultF(r2.invisibleRead.run[F], "xx")
      _ <- assertResultF(r3.invisibleRead.run[F], "")

      _ <- assertResultF(pc2.run[F], "xxx")
      _ <- assertResultF(r1.invisibleRead.run[F], "xxx")
      _ <- assertResultF(r2.invisibleRead.run[F], "xxx")
      _ <- assertResultF(r3.invisibleRead.run[F], "xxx")
    } yield ()
  }

  test("Impossible CAS should cause a runtime error") {
    for {
      ref <- React.newRef("a").run[F]
      r = ref.modify(_ + "b") >>> ref.modify(_ + "x").lmap(_ => ())
      res <- r.run[F].attempt
      _ <- res match {
        case Left(ex) => assertF(ex.getMessage.contains("Impossible k-CAS"))
        case Right(r) => failF(s"Unexpected success: ${r}")
      }
    } yield ()
  }

  test("Michael-Scott queue should work correctly") {
    for {
      q <- F.delay { new MichaelScottQueue[String] }
      _ <- assertResultF(q.unsafeToList, Nil)

      _ <- assertResultF(q.tryDeque.run, None)
      _ <- assertResultF(q.unsafeToList, Nil)

      _ <- q.enqueue("a")
      _ <- assertResultF(q.unsafeToList, List("a"))

      _ <- assertResultF(q.tryDeque.run, Some("a"))
      _ <- assertResultF(q.unsafeToList, Nil)
      _ <- assertResultF(q.tryDeque.run, None)
      _ <- assertResultF(q.unsafeToList, Nil)

      _ <- q.enqueue("a")
      _ <- assertResultF(q.unsafeToList, List("a"))
      _ <- q.enqueue("b")
      _ <- assertResultF(q.unsafeToList, List("a", "b"))
      _ <- q.enqueue("c")
      _ <- assertResultF(q.unsafeToList, List("a", "b", "c"))

      _ <- assertResultF(q.tryDeque.run, Some("a"))
      _ <- assertResultF(q.unsafeToList, List("b", "c"))

      _ <- q.enqueue("x")
      _ <- assertResultF(q.unsafeToList, List("b", "c", "x"))

      _ <- assertResultF(q.tryDeque.run, Some("b"))
      _ <- assertResultF(q.unsafeToList, List("c", "x"))
      _ <- assertResultF(q.tryDeque.run, Some("c"))
      _ <- assertResultF(q.unsafeToList, List("x"))
      _ <- assertResultF(q.tryDeque.run, Some("x"))
      _ <- assertResultF(q.tryDeque.run, None)
      _ <- assertResultF(q.unsafeToList, Nil)
    } yield ()
  }

  test("Michael-Scott queue should allow multiple producers and consumers") {
    val max = 10000
    for {
      q <- F.delay { new MichaelScottQueue[String] }
      produce = F.blocking {
        for (i <- 0 until max) {
          q.enqueue.unsafePerform(i.toString, this.kcasImpl)
        }
      }
      cs <- F.delay { new ConcurrentLinkedQueue[String] }
      stop <- F.delay { new AtomicBoolean(false) }
      consume = F.blocking {
        @tailrec
        def go(last: Boolean = false): Unit = {
          q.tryDeque.unsafeRun(this.kcasImpl) match {
            case Some(s) =>
              cs.offer(s)
              go(last = last)
            case None =>
              if (stop.get()) {
                if (last) {
                  // we're done:
                  ()
                } else {
                  // read one last time:
                  go(last = true)
                }
              } else {
                // retry:
                go(last = false)
              }
          }
        }
        go()
      }
      tsk = for {
        p1 <- produce.start
        c1 <- consume.start
        p2 <- produce.start
        c2 <- consume.start
        _ <- p1.joinWithNever
        _ <- p2.joinWithNever
        _ <- F.delay { stop.set(true) }
        _ <- c1.joinWithNever
        _ <- c2.joinWithNever
      } yield ()

      _ <- tsk.guarantee(F.delay { stop.set(true) })

      _ <- assertEqualsF(
        cs.asScala.toVector.sorted,
        (0 until max).toVector.flatMap(n => Vector(n.toString, n.toString)).sorted
      )
    } yield ()
  }

  test("Integration with IO should work") {
    val act: F[String] = for {
      ref <- React.newRef[String]("foo").run[F]
      _ <- ref.upd { (s, p: String) => (s + p, ()) }[F]("bar")
      res <- ref.getter.run[F]
    } yield res

    for {
      _ <- assertResultF(act, "foobar")
      _ <- assertResultF(act, "foobar")
    } yield ()
  }

  test("BooleanRefOps should provide guard/guardNot") {
    for {
      trueRef <- React.newRef(true).run[F]
      falseRef <- React.newRef(false).run[F]
      ft = React.ret(42)
      _ <- assertResultF(trueRef.guard(ft).run, Some(42))
      _ <- assertResultF(trueRef.guardNot(ft).run, None)
      _ <- assertResultF(falseRef.guard(ft).run, None)
      _ <- assertResultF(falseRef.guardNot(ft).run, Some(42))
    } yield ()
  }

  test("arrCas should be a correct CAS") {
    for {
      r1 <- React.newRef("s1").run[F]
      r2 <- React.newRef("s2").run[F]
      c1 = React.ret(("s1", "x1")) >>> React.arrCas(r1)
      c2 = React.ret(("s2", "x2")) >>> React.arrCas(r2)
      _ <- (c1 * c2).run[F]
      _ <- assertResultF(r1.getter.run[F], "x1")
      _ <- assertResultF(r2.getter.run[F], "x2")
    } yield ()
  }

  test("arrUpd should work".only) {
    for {
      r1 <- React.newRef("s1").run[F]
      r2 <- React.newRef("s2").run[F]
      _ <- (r1.arrModify(_ + "x") × r2.arrModify(_ + "y")).lmap[Unit](_ => ((), ())).run[F]
      _ <- assertResultF(r1.getter.run[F], "s1x")
      _ <- assertResultF(r2.getter.run[F], "s2y")
    } yield ()
  }
}
