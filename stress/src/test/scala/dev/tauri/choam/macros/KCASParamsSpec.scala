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
package macros

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalactic.TypeCheckedTripleEquals

import org.openjdk.jcstress.annotations._
import org.openjdk.jcstress.infra.results.ZZZ_Result

import kcas.KCAS

class KCASParamsSpec extends AnyFlatSpec with Matchers with TypeCheckedTripleEquals {

  "KCASParams macro" should "generate the parameterized subclasses" in {
    val sub1 = new DummyTest.DummyTestMCAS
    sub1.kcasImplPublic shouldBe theSameInstanceAs (KCAS.MCAS)
    val sub2 = new DummyTest.DummyTestNaiveKCAS
    sub2.kcasImplPublic shouldBe theSameInstanceAs (KCAS.NaiveKCAS)
    val sub3 = new DummyTest.DummyTestEMCAS
    sub3.kcasImplPublic shouldBe theSameInstanceAs (KCAS.EMCAS)
    for (sub <- List(sub1, sub2, sub3)) {
      sub
        .getClass()
        .getDeclaredMethod("actor1", classOf[ZZZ_Result])
        .getDeclaredAnnotations()
        .apply(0)
        .annotationType() shouldBe theSameInstanceAs (classOf[Actor])
      sub
        .getClass()
        .getDeclaredMethod("arbiter", classOf[ZZZ_Result])
        .getDeclaredAnnotations()
        .apply(0)
        .annotationType() shouldBe theSameInstanceAs (classOf[Arbiter])

      intercept[NoSuchMethodException] {
        sub
          .getClass()
          .getDeclaredMethod("foo", classOf[Int])
      }
    }
  }
}
