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

import internal.mcas.Mcas
import refs.Ref1

final class Ref1SpecP1 extends Ref1Spec {

  override def mkRef1[A](a: A, ctx: Mcas.ThreadContext): Ref1[A] =
    Ref1.unsafeUnpadded(a, ctx.refIdGen)
}

final class Ref1SpecU1 extends Ref1Spec {

  override def mkRef1[A](a: A, ctx: Mcas.ThreadContext): Ref1[A] =
    Ref1.unsafeUnpadded(a, ctx.refIdGen)
}

abstract class Ref1Spec extends BaseSpec with SpecDefaultMcas {

  def mkRef1[A](a: A, ctx: Mcas.ThreadContext): Ref1[A]

  final def mkRef1[A](a: A): Ref1[A] =
    this.mkRef1(a, this.mcasImpl.currentContext())

  test("toString format") {
    val pat = "Ref\\@[\\da-f]{16}".r
    assert(pat.matches(clue(mkRef1("a").toString)))
  }

  test("equals/toString") {
    val r1 = mkRef1[String]("a")
    val r2 = mkRef1[String]("a")
    assert(r1 ne r2)
    assert(r1 != r2)
    assert(r1.toString != r2.toString)
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
