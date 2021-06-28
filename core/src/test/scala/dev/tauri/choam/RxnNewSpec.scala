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

import cats.Monad
import cats.effect.IO

final class RxnNewSpec_NaiveKCAS_IO
  extends BaseSpecIO
  with SpecNaiveKCAS
  with RxnNewSpec[IO]

final class RxnNewSpec_NaiveKCAS_ZIO
  extends BaseSpecZIO
  with SpecNaiveKCAS
  with RxnNewSpec[zio.Task]

final class RxnNewSpec_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with RxnNewSpec[IO]

final class RxnNewSpec_FlakyEMCAS_IO
  extends BaseSpecIO
  with SpecFlakyEMCAS
  with RxnNewSpec[IO]

final class RxnNewSpec_EMCAS_ZIO
  extends BaseSpecZIO
  with SpecEMCAS
  with RxnNewSpec[zio.Task]

final class RxnNewSpec_FlakyEMCAS_ZIO
  extends BaseSpecZIO
  with SpecFlakyEMCAS
  with RxnNewSpec[zio.Task]

trait RxnNewSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  test("Creating and running deeply nested Rxn's should both be stack-safe") {
    def nest(
      n: Int,
      combine: (Rxn[Int, Int], Rxn[Int, Int]) => Rxn[Int, Int]
    ): Rxn[Int, Int] = {
      (1 to n).map(_ => Rxn.lift[Int, Int](_ + 1)).reduce(combine)
    }
    val N = 1024 * 1024
    val r1: Rxn[Int, Int] = nest(N, _ >>> _)
    val r2: Rxn[Int, Int] = nest(N, (x, y) => (x * y).map(_._1 + 1))
    val r3: Rxn[Int, Int] = nest(N, (x, y) => x.flatMap { _ => y })
    val r4: Rxn[Int, Int] = nest(N, _ >> _)
    val r5: Rxn[Int, Int] = nest(N, _ + _)
    val r6: Rxn[Int, Int] = nest(N, (x, y) => Rxn.unsafe.delayComputed(x.map(Rxn.ret(_) >>> y)))
    val r7: Rxn[Int, Int] = Monad[Rxn[Int, *]].tailRecM(N) { n =>
      if (n > 0) Rxn.lift[Int, Either[Int, Int]](_ => Left(n - 1))
      else Rxn.ret(Right(99))
    }
    assertEquals(r1.unsafePerform(42, this.kcasImpl), 42 + N)
    assertEquals(r2.unsafePerform(42, this.kcasImpl), 42 + N)
    assertEquals(r3.unsafePerform(42, this.kcasImpl), 42 + 1)
    assertEquals(r4.unsafePerform(42, this.kcasImpl), 42 + 1)
    assertEquals(r5.unsafePerform(42, this.kcasImpl), 42 + 1)
    assertEquals(r6.unsafePerform(42, this.kcasImpl), 42 + N)
    assertEquals(r7.unsafePerform(42, this.kcasImpl), 99)
  }

  test("Choice after >>>") {
    for {
      _ <- F.delay { this.assume(!this.isFlaky) }
      a <- Ref("a").run[F]
      b <- Ref("b").run[F]
      y <- Ref("y").run[F]
      p <- Ref("p").run[F]
      q <- Ref("q").run[F]
      rea = (
        (
          (Rxn.unsafe.cas(a, "a", "aa") + (Rxn.unsafe.cas(b, "b", "bb") >>> Rxn.unsafe.delay { _ =>
            this.kcasImpl.doSingleCas(y, "y", "-", this.kcasImpl.currentContext())
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

  test("first and second") {
    for {
      _ <- F.unit
      rea = Rxn.lift[Int, String](_.toString).first[Boolean]
      _ <- assertResultF(F.delay { rea.unsafePerform((42, true), this.kcasImpl) }, ("42", true))
      rea = Rxn.lift[Int, String](_.toString).second[Float]
      _ <- assertResultF(F.delay { rea.unsafePerform((1.5f, 21), this.kcasImpl) }, (1.5f, "21"))
    } yield ()
  }

  test("postCommit") {
    for {
      a <- Ref("a").run[F]
      b <- Ref("b").run[F]
      c <- Ref("c").run[F]
      rea = Rxn.unsafe.cas(a, "a", "aa").postCommit(
        Rxn.unsafe.cas(b, "b", "bb").postCommit(Rxn.unsafe.cas(c, "c", "cc"))
      ).postCommit(
        Rxn.unsafe.cas(b, "bb", "bbb").postCommit(Rxn.unsafe.cas(c, "cc", "ccc"))
      ).postCommit(
        Rxn.unsafe.cas(b, "bbb", "bbbb").postCommit(Rxn.unsafe.cas(c, "ccc", "cccc"))
      )
      _ <- assertResultF(rea.run[F], ())
      _ <- assertResultF(a.unsafeInvisibleRead.run, "aa")
      _ <- assertResultF(b.unsafeInvisibleRead.run, "bbbb")
      _ <- assertResultF(c.unsafeInvisibleRead.run, "cccc")
    } yield ()
  }

  test("postCommit on delayComputed") {
    for {
      _ <- F.delay { this.assume(!this.isFlaky) }
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
      _ <- F.delay { this.assume(!this.isFlaky) }
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

      def pop(head: Ref[Node]): Rxn[Any, String] = Rxn.unsafe.delayComputed {
        Rxn.unsafe.invisibleRead(head).flatMap { h =>
          Rxn.unsafe.invisibleRead(h.value).flatMap {
            case null =>
              // sentinel node, discard it and retry:
              Rxn.ref.read(h.next).flatMap { nxt =>
                Rxn.unsafe.cas(head, h, nxt)
              }.as(Rxn.unsafe.retry)
            case v =>
              // found the real head, pop it:
              Rxn.ret(Rxn.ref.read(h.next).flatMap { nxt =>
                Rxn.unsafe.cas(head, h, nxt).flatMap { _ =>
                  Rxn.unsafe.cas(h.value, v, v)
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
        Rxn.ref.read(ref).flatMap { node =>
          if (node eq null) {
            // there is an extra sentinel at the end:
            Rxn.ret(Right[(List[String], Ref[Node]), List[String]](acc.tail.reverse))
          } else {
            Rxn.ref.read(node.value).map { v =>
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
}
