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

import scala.concurrent.duration._

import munit.{ Location, FunSuite }

import mcas.equ

// TODO: rename to `BaseSpec`
trait BaseSpecA
  extends FunSuite
  with MUnitUtils {

  override def munitTimeout: Duration =
    super.munitTimeout * 2
}

trait MUnitUtils { this: FunSuite =>

  def assertSameInstance[A](
    obtained: A,
    expected: A,
    clue: String = "objects are not the same instance"
  )(implicit loc: Location): Unit = {
    assert(equ(this.clue(obtained), this.clue(expected)), clue)
  }

  def isIntCached(i: Int): Boolean = {
    val i1: java.lang.Integer = Integer.valueOf(i)
    val i2: java.lang.Integer = Integer.valueOf(i)
    i1 eq i2
  }

  def assertIntIsNotCached(i: Int): Unit = {
    assert(!isIntCached(i))
  }

  def assumeOpenJdk(): Unit = {
    assume(isOpenJdk(), "this test only runs on OpenJDK")
  }

  def assumeNotOpenJ9(): Unit = {
    assume(!isOpenJ9(), "this test doesn't run on OpenJ9")
  }

  def isOpenJdk(): Boolean = {
    val vmName = java.lang.System.getProperty("java.vm.name")
    vmName.contains("HotSpot") || vmName.contains("OpenJDK")
  }

  def isOpenJ9(): Boolean = {
    val vmName = java.lang.System.getProperty("java.vm.name")
    vmName.contains("OpenJ9")
  }
}
