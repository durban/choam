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
package skiplist

import cats.effect.IO
import cats.syntax.all._

import munit.{ ScalaCheckEffectSuite, CatsEffectSuite }

import org.scalacheck.effect.PropF

final class SkipListParallelSpec extends CatsEffectSuite with ScalaCheckEffectSuite with SkipListHelper {

  private final val N = 1024

  private final def repeat[A](action: => A): IO[List[A]] = {
    IO {
      val lb = List.newBuilder[A]
      var n = N
      while (n > 0) {
        lb += action
        n -= 1
      }
      lb.result()
    }
  }

  test("get race") {
    PropF.forAllF { (m: SkipListMap[Int, String], ks: Set[Int]) =>
      IO {
        for (k <- ks) {
          m.put(k, k.toString)
        }
      } *> IO.parTraverseN(4)(ks.toList) { k =>
        repeat { assertEquals(m.get(k), Some(k.toString())) }
      }.void
    }
  }

  test("put race") {
    PropF.forAllF { (m: SkipListMap[Int, String], m1: Map[Int, String], _m2: Map[Int, String]) =>
      for {
        original <- IO {
          val b = Map.newBuilder[Int, String]
          m.foreach { (k, v) =>
            b += ((k, v))
          }
          b.result()
        }
        m2 = _m2 -- m1.keySet // make sure they're disjoint
        put1 = IO { for ((k1, v1) <- m1) yield (k1, m.put(k1, v1)) }
        put2 = IO { for ((k2, v2) <- m2) yield (k2, m.put(k2, v2 + "_A")) }
        put3 = IO { for ((k2, v2) <- m2) yield (k2, m.put(k2, v2 + "_B")) }

        _ <- IO.both(put1, IO.both(put2, put3)).flatMap { r =>
          val (r1, (r2, r3)) = r
          IO {
            for ((k, ov) <- r1) {
              assertEquals(m.get(k), Some(m1(k)))
              ov match {
                case Some(oldValue) => assertEquals(oldValue, original(k))
                case None => ()
              }
            }
            assertEquals(r2.keySet, r3.keySet)
            for ((k, ov) <- r2) {
              val cv = m.get(k).get
              (ov, r3(k)) match {
                case (Some(ov1), Some(ov2)) =>
                  assert(
                    ((ov1 === original(k)) && (ov2 === m2(k) + "_A") && (cv === m2(k) + "_B")) ||
                    ((ov1 === m2(k) + "_B") && (ov2 === original(k)) && (cv === m2(k) + "_A"))
                  )
                case (Some(ov), None) =>
                  assertEquals(ov, m2(k) + "_B")
                  assertEquals(cv, m2(k) + "_A")
                  assert(!original.contains(k))
                case (None, Some(ov)) =>
                  assertEquals(ov, m2(k) + "_A")
                  assertEquals(cv, m2(k) + "_B")
                  assert(!original.contains(k))
                case (None, None) =>
                  fail("neither put have seen an old value")
              }
            }
          }
        }
      } yield ()
    }
  }

  test("del / foreach race") {
    PropF.forAllF { (m: SkipListMap[Int, String], k: Int, v: String) =>
      for {
        _ <- IO { m.del(k) }
        initial <- IO { SkipListHelper.listFromSkipList(m) }
        _ <- IO { m.put(k, v) }
        inserted <- IO { SkipListHelper.listFromSkipList(m) }
        r <- IO.both(
          IO { SkipListHelper.listFromSkipList(m) },
          IO.both(IO { m.del(k) }, IO { m.del(k) })
        )
        (lst, (d1, d2)) = r
        _ <- IO { assert((lst === initial) || (lst === inserted), s"lst == ${lst}; initial == ${initial}; inserted == ${inserted}") }
        _ <- IO { assert(d1 ^ d2) } // one true, one false
      } yield ()
    }
  }
}
