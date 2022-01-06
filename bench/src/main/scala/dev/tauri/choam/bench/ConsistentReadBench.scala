/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

/**
 * The old implementation of `consistentRead` used
 * `invisibleRead` and `cas`. The new one uses `updWith`.
 * They're essentially the same, the new one is just "nicer".
 *
 * The old `updWith` was itself derived from `invisibleRead`
 * and `cas`. The new one is a primitive.
 */
@Fork(2)
@deprecated("so that we can call the old method", since = "2021-03-27")
class ConsistentReadBench {

  import ConsistentReadBench._

  @Benchmark
  def crWithInvisibleRead(s: St, k: KCASImplState): (String, String) = {
    s.crWithInvisibleRead.unsafePerform((), k.kcasImpl)
  }

  @Benchmark
  def crWithOldUpdWith(s: St, k: KCASImplState): (String, String) = {
    s.crWithOldUpdWith.unsafePerform((), k.kcasImpl)
  }

  @Benchmark
  def crWithNewUpdWith(s: St, k: KCASImplState): (String, String) = {
    s.crWithNewUpdWith.unsafePerform((), k.kcasImpl)
  }
}

object ConsistentReadBench {

  @State(Scope.Thread)
  @deprecated("so that we can call the old method", since = "2021-03-27")
  class St {
    val r1: Ref[String] =
      Ref.unsafe(s"r1: ${ThreadLocalRandom.current().nextLong()}")
    val r2: Ref[String] =
      Ref.unsafe(s"r2: ${ThreadLocalRandom.current().nextLong()}")
    val crWithInvisibleRead: Axn[(String, String)] =
      Rxn.consistentReadOld(r1, r2)
    val crWithOldUpdWith: Axn[(String, String)] =
      Rxn.consistentReadWithOldUpdWith(r1, r2)
    val crWithNewUpdWith: Axn[(String, String)] =
      Rxn.consistentRead(r1, r2)
  }
}
