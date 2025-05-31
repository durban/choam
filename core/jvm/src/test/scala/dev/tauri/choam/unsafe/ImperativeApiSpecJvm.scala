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
package unsafe

import java.util.concurrent.CountDownLatch

import scala.concurrent.duration._
import scala.concurrent.Future // TODO: use IO instead of this
import scala.concurrent.ExecutionContext.Implicits.global

import munit.FunSuite

import core.Ref

final class ImperativeApiSpecJvm extends FunSuite with MUnitUtils {

  import api._

  override def munitTimeout: Duration =
    Duration.Inf

  test("Retries") {
    val r1: Ref[Int] = newRef(0)
    val r2: Ref[Int] = newRef(0)
    val latch1 = new CountDownLatch(1)
    val latch2 = new CountDownLatch(1)

    def txn1()(implicit ir: InRxn): Unit = {
      val v1 = readRef(r1)
      latch1.countDown()
      latch2.await()
      val v2 = readRef(r2)
      assertEquals(v1, v2)
    }

    def txn2()(implicit ir: InRxn): Unit = {
      val v1 = readRef(r1)
      val nv = v1 + 1
      writeRef(r1, nv)
      writeRef(r2, nv)
    }

    val fut1 = Future {
      atomically { implicit ir =>
        txn1()
      }
    }
    val fut2 = Future {
      latch1.await()
      atomically { implicit ir =>
        txn2()
      }
      latch2.countDown()
    }

    Future.sequence(fut1 :: fut2 :: Nil)
  }
}
