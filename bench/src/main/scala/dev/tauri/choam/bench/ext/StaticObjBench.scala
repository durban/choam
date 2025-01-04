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
package bench
package ext

import org.openjdk.jmh.annotations._

@Fork(2)
@Threads(2)
class StaticObjBench {

  import StaticObjBench._

  @Benchmark
  def callInherited(t: ThSt): Unit = {
    t.inherited()
  }

  @Benchmark
  def callStatic(t: ThSt): Unit = {
    t.static()
  }

  @Benchmark
  def callObject(t: ThSt): Unit = {
    t.obj()
  }
}

object StaticObjBench {

  @State(Scope.Thread)
  class ThSt extends BaseThSt {

    final def inherited(): Unit = {
      this.spin()
    }

    final def static(): Unit = {
      StaticObjBenchHelper.spin()
    }

    final def obj(): Unit = {
      ThStObj.spin()
    }
  }
}

@State(Scope.Thread)
sealed abstract class BaseThSt {
  protected final def spin(): Unit = {
    Thread.onSpinWait()
  }
}

object ThStObj {
  final def spin(): Unit = {
    Thread.onSpinWait()
  }
}
