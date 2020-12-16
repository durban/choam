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
package data

import scala.collection.immutable

class MapSpecNaiveKCAS
  extends MapSpec
  with SpecNaiveKCAS

class MapSpecEMCAS
  extends MapSpec
  with SpecEMCAS

abstract class MapSpec extends BaseSpec {

  def mkEmptyMap[K, V](): Map[K, V] =
    Map.naive[K, V].unsafeRun

  "Map" should "perform put correctly" in {
    val m = mkEmptyMap[Int, Int]()
    (React.pure(42 -> 21) >>> m.put).unsafeRun
    m.snapshot.unsafeRun should === (immutable.Map(42 -> 21))
    (React.pure(44 -> 22) >>> m.put).unsafeRun
    m.snapshot.unsafeRun should === (immutable.Map(42 -> 21, 44 -> 22))
    (React.pure(42 -> 0) >>> m.put).unsafeRun
    m.snapshot.unsafeRun should === (immutable.Map(42 -> 0, 44 -> 22))
  }

  it should "perform get correctly" in {
    val m = mkEmptyMap[Int, Int]()
    (React.pure(42 -> 21) >>> m.put).unsafeRun
    m.snapshot.unsafeRun should === (immutable.Map(42 -> 21))
    m.get.unsafePerform(42) should === (Some(21))
    m.snapshot.unsafeRun should === (immutable.Map(42 -> 21))
    m.get.unsafePerform(99) should === (None)
    m.snapshot.unsafeRun should === (immutable.Map(42 -> 21))
  }

  it should "perform del correctly" in {
    val m = mkEmptyMap[Int, Int]()
    (React.pure(42 -> 21) >>> m.put).unsafeRun
    m.snapshot.unsafeRun should === (immutable.Map(42 -> 21))
    (React.pure(56) >>> m.del).unsafeRun should === (false)
    m.snapshot.unsafeRun should === (immutable.Map(42 -> 21))
    (React.pure(42) >>> m.del).unsafeRun should === (true)
    m.snapshot.unsafeRun should === (immutable.Map.empty[Int, Int])
    (React.pure(42) >>> m.del).unsafeRun should === (false)
    m.snapshot.unsafeRun should === (immutable.Map.empty[Int, Int])
  }

  it should "perform replace correctly" in {
    val m = mkEmptyMap[Int, String]()
    (React.pure(42 -> "foo") >>> m.put).unsafeRun
    m.snapshot.unsafeRun should === (immutable.Map(42 -> "foo"))
    m.replace.unsafePerform((42, "xyz", "bar")) should === (false)
    m.snapshot.unsafeRun should === (immutable.Map(42 -> "foo"))
    m.replace.unsafePerform((42, "foo", "bar")) should === (true)
    m.snapshot.unsafeRun should === (immutable.Map(42 -> "bar"))
    m.replace.unsafePerform((99, "foo", "bar")) should === (false)
    m.snapshot.unsafeRun should === (immutable.Map(42 -> "bar"))
  }

  it should "perform remove correctly" in {
    val m = mkEmptyMap[Int, String]()
    (React.pure(42 -> "foo") >>> m.put).unsafeRun
    (React.pure(99 -> "bar") >>> m.put).unsafeRun
    m.snapshot.unsafeRun should === (immutable.Map(42 -> "foo", 99 -> "bar"))
    m.remove.unsafePerform(42 -> "x") should === (false)
    m.snapshot.unsafeRun should === (immutable.Map(42 -> "foo", 99 -> "bar"))
    m.remove.unsafePerform(42 -> "foo") should === (true)
    m.snapshot.unsafeRun should === (immutable.Map(99 -> "bar"))
    m.remove.unsafePerform(42 -> "foo") should === (false)
    m.snapshot.unsafeRun should === (immutable.Map(99 -> "bar"))
  }

  it should "perform clear correctly" in {
    val m = mkEmptyMap[Int, String]()
    (React.pure(42 -> "foo") >>> m.put).unsafeRun
    (React.pure(99 -> "bar") >>> m.put).unsafeRun
    m.snapshot.unsafeRun should === (immutable.Map(42 -> "foo", 99 -> "bar"))
    m.clear.unsafeRun should === (immutable.Map(42 -> "foo", 99 -> "bar"))
    m.snapshot.unsafeRun should === (immutable.Map.empty[Int, String])
    m.clear.unsafeRun should === (immutable.Map.empty[Int, String])
    m.snapshot.unsafeRun should === (immutable.Map.empty[Int, String])
  }
}
