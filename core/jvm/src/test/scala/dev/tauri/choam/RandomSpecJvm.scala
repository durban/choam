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

import java.security.{ SecureRandom, Provider }

final class RandomSpecJvm extends BaseSpecA {

  test("UUID SecureRandom") {
    val f = Class.forName("java.util.UUID$Holder").getDeclaredField("numberGenerator")
    f.setAccessible(true)
    val s = f.get(null).asInstanceOf[SecureRandom]
    println("UUID SecureRandom: " + s.toString)
    val ff = classOf[SecureRandom].getDeclaredField("threadSafe")
    ff.setAccessible(true)
    println("UUID SecureRandom is thread safe: " + ff.get(s).asInstanceOf[Boolean].toString)
    val fp = classOf[SecureRandom].getDeclaredField("provider")
    fp.setAccessible(true)
    val provider = fp.get(s).asInstanceOf[Provider]
    println("UUID SecureRandom provider: " + provider.toString)
  }

  test("Default SecureRandom") {
    val s = new SecureRandom()
    s.nextBytes(new Array[Byte](20)) // force seed
    println("Default SecureRandom: " + s.toString)
    val ff = classOf[SecureRandom].getDeclaredField("threadSafe")
    ff.setAccessible(true)
    println("Default SecureRandom is thread safe: " + ff.get(s).asInstanceOf[Boolean].toString)
    val fp = classOf[SecureRandom].getDeclaredField("provider")
    fp.setAccessible(true)
    val provider = fp.get(s).asInstanceOf[Provider]
    println("Default SecureRandom provider: " + provider.toString)
  }
}
