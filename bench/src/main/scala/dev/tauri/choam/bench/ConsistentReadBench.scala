/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._

import util._
import kcas.Ref

/**
 * The old implementation of `consistentRead` used
 * `invisibleRead` and `cas`. The new one uses `updWith`.
 * They're essentially the same, the new one is just "nicer".
 *
 * This benchmark is to show that there is no real
 * performance difference between them.
 */
@Fork(2)
@deprecated("so that we can call the old method", since = "2021-03-27")
class ConsistentReadBench {

  import ConsistentReadBench._

  @Benchmark
  def withInvisibleRead(s: St, k: KCASImplState): (String, String) = {
    s.crWithInvisibleRead.unsafePerform((), k.kcasImpl)
  }

  @Benchmark
  def withUpdWith(s: St, k: KCASImplState): (String, String) = {
    s.crWithUpdWith.unsafePerform((), k.kcasImpl)
  }
}

object ConsistentReadBench {

  @State(Scope.Thread)
  @deprecated("so that we can call the old method", since = "2021-03-27")
  class St {
    val r1 =
      Ref.unsafe(s"r1: ${ThreadLocalRandom.current().nextLong()}")
    val r2 =
      Ref.unsafe(s"r2: ${ThreadLocalRandom.current().nextLong()}")
    val crWithInvisibleRead: Action[(String, String)] =
      React.consistentReadOld(r1, r2)
    val crWithUpdWith: Action[(String, String)] =
      React.consistentRead(r1, r2)
  }
}
