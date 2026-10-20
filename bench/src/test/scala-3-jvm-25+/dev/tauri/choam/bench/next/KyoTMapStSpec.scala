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
package bench
package next

import kyo.{ Kyo, STM, Abort }

import munit.FunSuite

import DataMapBench2.forkAndBlockKyo

final class KyoTMapStSpec extends FunSuite with MUnitUtils {

  private[this] final val N = 131072

  test("running kyo-stm transactions") {
    val st = new DataMapBench2.KyoTMapSt
    st.setupImpl(N)
    val m = st.map
    assertEquals(forkAndBlockKyo(STM.run(m.size)), N)
    forkAndBlockKyo {
      Kyo.foreach(st.keys) { key =>
        STM.run(m.contains(key)).map { ok =>
          if (ok) ()
          else Abort.fail(new AssertionError(s"map does not contain key ${key}"))
        }
      }
    }
  }
}
