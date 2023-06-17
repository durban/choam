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

import java.security.{ SecureRandom => JSecureRandom, Provider }

abstract class EnvironmentSpecPlatform extends BaseSpec {

  test("Check SecureRandom") {
    val sr = new JSecureRandom
    printSecureRandomInfo(sr, prefix = "SecureRandom")
  }

  private def printSecureRandomInfo(sr: JSecureRandom, prefix: String): Unit = {
    println(s"${prefix} class: ${sr.getClass().getCanonicalName()}")
    println(s"${prefix} algorithm: ${sr.getAlgorithm()}")
    println(s"${prefix} parameters: ${sr.getParameters()}")
    val pr = sr.getProvider()
    println(s"${prefix} provider class: ${pr.getClass().getCanonicalName()}")
    println(s"${prefix} provider name: ${pr.getName()}")
    println(s"${prefix} provider version: ${pr.getVersionStr()}")
    println(s"${prefix} provider info: ${pr.getInfo()}")
    println(s"${prefix} is thread safe: ${isThreadSafe(sr, pr)}")
  }

  private def isThreadSafe(sr: JSecureRandom, pr: Provider): Boolean = {
    (sr.getAlgorithm() ne null) && java.lang.Boolean.parseBoolean(
      pr.getProperty("SecureRandom." + sr.getAlgorithm() + " ThreadSafe", "false")
    )
  }
}
