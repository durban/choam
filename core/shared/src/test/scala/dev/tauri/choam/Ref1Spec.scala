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

import cats.{ Show, Hash }

import core.Ref
import internal.mcas.Mcas

final class Ref1SpecP1 extends Ref1Spec {

  override def mkRef1[A](a: A, ctx: Mcas.ThreadContext): Ref[A] =
    Ref.unsafe(a, AllocationStrategy.Padded, ctx.refIdGen)
}

final class Ref1SpecU1 extends Ref1Spec {

  override def mkRef1[A](a: A, ctx: Mcas.ThreadContext): Ref[A] =
    Ref.unsafe(a, AllocationStrategy.Unpadded, ctx.refIdGen)
}

abstract class Ref1Spec extends BaseSpec with SpecDefaultMcas {

  def mkRef1[A](a: A, ctx: Mcas.ThreadContext): Ref[A]

  final def mkRef1[A](a: A): Ref[A] =
    this.mkRef1(a, this.mcasImpl.currentContext())

  test("toString format") {
    val pat = "Ref\\@[\\da-f]{16}".r
    val r = mkRef1("a")
    assert(pat.matches(clue(r.toString)))
    assertEquals(Show[Ref[String]].show(r), r.toString)
  }

  test("equals/toString") {
    val r1 = mkRef1[String]("a")
    val r2 = mkRef1[String]("a")
    assert(r1 ne r2)
    assert(r1 != r2)
    assert(r1.toString != r2.toString)
  }

  test("hash") {
    val r = mkRef1[String]("a")
    assertEquals(Hash[Ref[String]].hash(r), r.##)
  }

  test("read/write/cas") {
    val r = mkRef1[String]("a").loc
    assert(r.unsafeGetV() eq "a")
    r.unsafeSetV("b")
    assert(r.unsafeGetV() eq "b")
    assert(r.unsafeCasV("b", "c"))
    assert(r.unsafeGetV() eq "c")
  }
}
