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
package core

import cats.data.Ior

import org.scalacheck.{ Gen, Arbitrary, Cogen }

import internal.mcas.{ Mcas, RefIdGen }

trait TestInstancesBase {

  def mcasImpl: Mcas

  protected def rigInstance: RefIdGen

  /**
   * A `Gen` utility which works kind of like `F.delay { ... }`
   *
   * We need this, because `Gen.delay` works differently (it
   * works kind of like `F.defer`).
   */
  private[choam] final def genDelay[A](block: => A): Gen[A] =
    Gen.delay { Gen.const(block) }

  implicit def arbIor[A, B](implicit arbA: Arbitrary[A], arbB: Arbitrary[B]): Arbitrary[Ior[A, B]] = Arbitrary {
    Gen.oneOf(
      arbA.arbitrary.map(Ior.left),
      arbB.arbitrary.map(Ior.right),
      arbA.arbitrary.flatMap { a => arbB.arbitrary.map { b => Ior.both(a, b) } },
    )
  }

  implicit def cogenIor[A, B](implicit cogA: Cogen[A], cogB: Cogen[B]): Cogen[Ior[A, B]] = {
    Cogen { (seed, ior) =>
      ior.fold(
        a => cogA.perturb(seed.next, a),
        b => cogB.perturb(seed.next.next, b),
        (a, b) => cogB.perturb(cogA.perturb(seed.next.next.next, a), b)
      )
    }
  }
}
