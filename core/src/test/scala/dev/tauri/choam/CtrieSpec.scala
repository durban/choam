/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.Eq
import cats.implicits._
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks

abstract class CtrieSpec extends BaseSpec with ScalaCheckDrivenPropertyChecks {

  val hs: Int => Int = { x => x }

  def newEmpty(
    hashFunc: Int => Int = hs,
    eqFunc: (Int, Int) => Boolean = _ == _
  ): Ctrie[Int, String] = {
    new Ctrie[Int, String](hashFunc, Eq.instance(eqFunc))
  }

  "Ctrie#lookup" should "not find anything in an empty trie" in {
    val ct = newEmpty()
    forAll { i: Int =>
      ct.lookup.unsafePerform(i) should === (None)
    }
  }

  it should "find a previously inserted single key" in {
    forAll { (k: Int, i: Int) =>
      val ct = newEmpty()
      ct.insert.unsafePerform(k -> k.toString)
      ct.lookup.unsafePerform(k) should === (Some(k.toString))
      if (i =!= k) {
        ct.lookup.unsafePerform(i) should === (None)
      }
    }
  }

  it should "find all previously inserted keys" in {
    forAll { (ks: Set[Int], x: Int) =>
      val ct = newEmpty()
      val shadow = new scala.collection.mutable.HashSet[Int]
      for (k <- ks) {
        ct.insert.unsafePerform(k -> k.toString)
        shadow += k
        for (i <- shadow) {
          ct.lookup.unsafePerform(i) should === (Some(i.toString))
        }
        if (!shadow.contains(x)) {
          ct.lookup.unsafePerform(x) should === (None)
        }
      }
    }
  }

  it should "find an equal key which is not equal according to universal equality" in {
    val ct = newEmpty(_ % 4, (x, y) => (x % 8) == (y % 8))
    ct.insert.unsafePerform(0 -> "0")
    ct.lookup.unsafePerform(0) should === (Some("0"))
    ct.lookup.unsafePerform(8) should === (Some("0"))
    ct.insert.unsafePerform(4 -> "4")
    ct.lookup.unsafePerform(0) should === (Some("0"))
    ct.lookup.unsafePerform(8) should === (Some("0"))
    ct.lookup.unsafePerform(4) should === (Some("4"))
    ct.lookup.unsafePerform(12) should === (Some("4"))
  }

  "Ctrie#insert" should "handle hash collisions correctly" in {
    forAll { (ks: Set[Int], x: Int) =>
      val ct = newEmpty(_ % 8)
      ct.insert.unsafePerform(x -> x.toString)
      ct.lookup.unsafePerform(x) should === (Some(x.toString))
      ct.insert.unsafePerform(x + 8 -> (x + 8).toString)
      ct.lookup.unsafePerform(x) should === (Some(x.toString))
      ct.lookup.unsafePerform(x + 8) should === (Some((x + 8).toString))
      ct.insert.unsafePerform(x + 16 -> (x + 16).toString)
      ct.lookup.unsafePerform(x) should === (Some(x.toString))
      ct.lookup.unsafePerform(x + 8) should === (Some((x + 8).toString))
      ct.lookup.unsafePerform(x + 16) should === (Some((x + 16).toString))
      ct.insert.unsafePerform(x + 1 -> (x + 1).toString)
      ct.lookup.unsafePerform(x) should === (Some(x.toString))
      ct.lookup.unsafePerform(x + 8) should === (Some((x + 8).toString))
      ct.lookup.unsafePerform(x + 16) should === (Some((x + 16).toString))
      ct.lookup.unsafePerform(x + 1) should === (Some((x + 1).toString))
      ct.insert.unsafePerform(x + 9 -> (x + 9).toString)
      ct.lookup.unsafePerform(x) should === (Some(x.toString))
      ct.lookup.unsafePerform(x + 8) should === (Some((x + 8).toString))
      ct.lookup.unsafePerform(x + 16) should === (Some((x + 16).toString))
      ct.lookup.unsafePerform(x + 1) should === (Some((x + 1).toString))
      ct.lookup.unsafePerform(x + 9) should === (Some((x + 9).toString))
      for (k <- ks) {
        ct.insert.unsafePerform(k -> k.toString)
        ct.lookup.unsafePerform(k) should === (Some(k.toString))
      }
      ct.insert.unsafePerform(x + 17 -> (x + 17).toString)
      ct.lookup.unsafePerform(x + 17) should === (Some((x + 17).toString))
    }
  }

  "Ctrie#debugStr" should "pretty print the trie structure" in {
    val ct = new Ctrie[Int, String](_ % 4, Eq.instance(_ % 8 == _ % 8))
    ct.insert.unsafePerform(0 -> "0")
    ct.insert.unsafePerform(1 -> "1")
    ct.insert.unsafePerform(4 -> "4")
    ct.insert.unsafePerform(5 -> "5")
    ct.insert.unsafePerform(8 -> "8") // overwrites 0
    ct.insert.unsafePerform(9 -> "9") // overwrites 1
    val expStr = """INode -> CNode 3
    |  INode -> CNode 1
    |    INode -> CNode 1
    |      INode -> CNode 1
    |        INode -> CNode 1
    |          INode -> CNode 1
    |            INode -> CNode 1
    |              INode -> LNode(8 -> 8, 4 -> 4)
    |  INode -> CNode 1
    |    INode -> CNode 1
    |      INode -> CNode 1
    |        INode -> CNode 1
    |          INode -> CNode 1
    |            INode -> CNode 1
    |              INode -> LNode(9 -> 9, 5 -> 5)""".stripMargin

    ct.debugStr should === (expStr)
  }
}

final class CtrieSpecNaiveKCAS
  extends CtrieSpec
  with SpecNaiveKCAS

final class CtrieSpecEMCAS
  extends CtrieSpec
  with SpecEMCAS
