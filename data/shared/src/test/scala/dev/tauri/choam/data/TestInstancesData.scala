/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package data

import cats.{ Eq, Hash, Order }
import cats.syntax.all._

import org.scalacheck.{ Arbitrary, Cogen, Gen }
import org.scalacheck.rng.Seed

import core.TestInstancesCore

trait TestInstancesData extends TestInstancesDataLowPrio {

  implicit def arbDataMap[K, V](
    implicit
    arbK: Arbitrary[K],
    hashK: Hash[K],
    ordK: Order[K],
    arbV: Arbitrary[V],
    cogV: Cogen[V],
  ): Arbitrary[data.Map[K, V]] = Arbitrary {
    implicitly[Arbitrary[List[(K, V)]]].arbitrary.flatMap { kvs =>
      Gen.oneOf(
        genDelay(this.unsafePerformForTest(data.Map.simpleHashMap[K, V])),
        genDelay(this.unsafePerformForTest(data.Map.simpleOrderedMap[K, V])),
        genDelay(this.unsafePerformForTest(data.Map.hashMap[K, V])),
        genDelay(this.unsafePerformForTest(data.Map.orderedMap[K, V])),
        Gen.lzy {
          val genVV = implicitly[Arbitrary[V => V]].arbitrary
          for {
            vv1 <- genVV
            vv2 <- genVV
            m <- this.arbDataMap[K, V].arbitrary
          } yield m.imap(vv1)(vv2)
        },
        Gen.lzy {
          for {
            f1 <- implicitly[Arbitrary[Tuple2[V, V] => V]].arbitrary
            f2 <- implicitly[Arbitrary[V => (V, V)]].arbitrary
            m1 <- this.arbDataMap[K, V].arbitrary
            m2 <- this.arbDataMap[K, V].arbitrary
          } yield data.Map.invariantSemigroupalForDevTauriChoamDataMap[K].product(m1, m2).imap(f1)(f2)
        },
      ).flatMap(insertIntoMap(_, kvs))
    }
  }

  private[choam] final def insertIntoMap[M[k, v] <: Map[k, v], K, V](m: M[K, V], kvs: List[(K, V)]): Gen[M[K, V]] = {
    genDelay {
      this.unsafePerformForTest(kvs.traverse_ { case (k, v) =>
        m.put(k, v)
      }.as(m))
    }
  }

  implicit def eqDataMap[K, V](
    implicit
    arbK: Arbitrary[K],
    eqV: Eq[V],
    arbV: Arbitrary[V],
  ): Eq[data.Map[K, V]] = { (m1, m2) =>
    val k = arbK.arbitrary.pureApply(Gen.Parameters.default, Seed(42L))
    val v = arbV.arbitrary.pureApply(Gen.Parameters.default, Seed(42L))
    this.unsafePerformForTest(m1.put(k, v)) : Unit
    val v1 = this.unsafePerformForTest(m1.get(k)).getOrElse(throw new AssertionError)
    this.unsafePerformForTest(m2.get(k)) match {
      case Some(v2) => eqV.eqv(v1, v2)
      case None => false
    }
  }
}

trait TestInstancesDataLowPrio extends TestInstancesCore { this: TestInstancesData =>

  implicit def arbDataMapExtra[K, V](
    implicit
    arbK: Arbitrary[K],
    hashK: Hash[K],
    ordK: Order[K],
    arbV: Arbitrary[V],
  ): Arbitrary[data.Map.Extra[K, V]] = Arbitrary {
    implicitly[Arbitrary[List[(K, V)]]].arbitrary.flatMap { kvs =>
      Gen.oneOf(
        genDelay(this.unsafePerformForTest(data.Map.simpleHashMap[K, V])),
        genDelay(this.unsafePerformForTest(data.Map.simpleOrderedMap[K, V])),
      ).flatMap(this.insertIntoMap(_, kvs))
    }
  }

  implicit def eqDataMapExtra[K, V](
    implicit
    arbK: Arbitrary[K],
    eqV: Eq[V],
    arbV: Arbitrary[V],
  ): Eq[data.Map.Extra[K, V]] = { (a, b) =>
    if (this.eqDataMap[K, V].eqv(a, b)) {
      // we can do some extra checks on Extra:
      val aKeys = this.unsafePerformForTest(a.keys)
      val aOk = aKeys.forall { k =>
        val aValue = this.unsafePerformForTest(a.get(k)).getOrElse(throw new AssertionError)
        val bValue = this.unsafePerformForTest(b.get(k))
        bValue match {
          case Some(bValue) => eqV.eqv(aValue, bValue)
          case None => false
        }
      }
      val bKeys = this.unsafePerformForTest(b.keys)
      val bOk = bKeys.forall { k =>
        val bValue = this.unsafePerformForTest(b.get(k)).getOrElse(throw new AssertionError)
        val aValue = this.unsafePerformForTest(a.get(k))
        aValue match {
          case Some(aValue) => eqV.eqv(aValue, bValue)
          case None => false
        }
      }
      aOk && bOk
    } else {
      false
    }
  }
}
