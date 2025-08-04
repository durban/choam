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
package internal
package skiplist

import cats.kernel.Order
import cats.Applicative
import cats.syntax.all._

import org.scalacheck.{ Arbitrary, Gen }

trait SkipListHelper {

  implicit def arbSkipListMap[K, V](implicit ordK: Order[K], arbK: Arbitrary[K], arbV: Arbitrary[V]): Arbitrary[SkipListMap[K, V]] = {
    Arbitrary {
      for {
        m <- Gen.delay { new SkipListMap[K, V] }
        ks <- implicitly[Arbitrary[Set[K]]].arbitrary
        _ <- ks.toList.traverse_ { k =>
          arbV.arbitrary.flatMap { v =>
            Gen.delay {
              m.put(k, v)
            }
          }
        }
      } yield m
    }
  }

  implicit private[this] final def applicativeForGen: Applicative[Gen] = {
    SkipListHelper.applicativeForGen
  }
}

object SkipListHelper {

  def listFromSkipList[K, V](m: SkipListMap[K, V]): List[(K, V)] = {
    val lb = List.newBuilder[(K, V)]
    m.foreachAndSum { (k, v) => lb += ((k, v)); 0 }
    lb.result()
  }

  def listFromSkipListIterator[K, V](m: SkipListMap[K, V]): List[(K, V)] = {
    val lb = List.newBuilder[(K, V)]
    m.iterator.foreach { kv => lb += (kv) }
    lb.result()
  }

  private[SkipListHelper] val applicativeForGen: Applicative[Gen] = new Applicative[Gen] {

    def pure[A](x: A): Gen[A] =
      Gen.const(x)

    def ap[A, B](ff: Gen[A => B])(fa: Gen[A]): Gen[B] =
      ff.flatMap { f => fa.map(f) }
  }
}
