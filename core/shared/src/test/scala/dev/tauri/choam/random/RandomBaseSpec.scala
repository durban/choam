/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package random

import java.util.{ Arrays, UUID }
import java.nio.{ ByteBuffer, ByteOrder }

import internal.mcas.Mcas

abstract class RandomBaseSpec extends munit.FunSuite {

  test("byteArrayViewVarHandle") {
    final class DummyRng(const: Array[Byte]) extends RandomBase {
      final override def nextBytes(n: Int): Axn[Array[Byte]] = {
        require(n == 8)
        Axn.unsafe.delay {
          Arrays.copyOf(const, const.length)
        }
      }
    }
    val rng1 = new DummyRng(Array.fill(8)(0x01.toByte))
    assertEquals(rng1.nextLong.unsafeRun(Mcas.DefaultMcas), 0x0101010101010101L)
    val rng2 = new DummyRng({ val arr = Array.fill(8)(0x01.toByte); arr(0) = 0xff.toByte; arr })
    assertEquals(rng2.nextLong.unsafeRun(Mcas.DefaultMcas), 0x01010101010101ffL)
  }

  test("ByteBuffer endianness") {
    val arr = new Array[Byte](8)
    assertEquals(ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN).getLong(), 0L)
    arr(0) = 1.toByte
    assertEquals(ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN).getLong(), 1L)
    arr(0) = 0.toByte
    arr(7) = 1.toByte
    assertEquals(ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN).getLong(), 72057594037927936L)
    arr(7) = 0xff.toByte
    assertEquals(ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN).getLong(), -72057594037927936L)
  }

  test("RxnUuidGen") {
    val gen = new RxnUuidGen[Any](OsRng.mkNew())
    val first = gen.unsafeRandomUuid()
    def checkUuid(u: UUID): Unit = {
      assertEquals(u.variant, 2)
      assertEquals(u.version, 4)
      assertNotEquals(u, first)
      assertNotEquals(u.getMostSignificantBits, first.getMostSignificantBits)
      assertNotEquals(u.getLeastSignificantBits, first.getLeastSignificantBits)
      assertNotEquals(u.hashCode(), 0)
    }
    for (_ <- 1 to 1024) {
      // at most 2**-50 chance of accidental failure
      checkUuid(gen.unsafeRandomUuid())
    }
    checkUuid(gen.uuidFromRandomBytes(Array.fill[Byte](16)(0x00.toByte)))
    checkUuid(gen.uuidFromRandomBytes(Array.fill[Byte](16)(0xff.toByte)))
    checkUuid(gen.uuidFromRandomBytes(Array.fill[Byte](16)(0x55.toByte)))
    val aa = gen.uuidFromRandomBytes(Array.fill[Byte](16)(0xaa.toByte))
    checkUuid(aa)
    assertEquals(aa.toString, "aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa")
  }
}
