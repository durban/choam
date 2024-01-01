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

import java.security.{ SecureRandom => JSecureRandom }

private[random] abstract class OsRngPlatform {

  /**
   * Creates (and initializes) a new `OsRng`
   * RNG instance, which will get secure random
   * bytes directly from the JS runtime.
   *
   * Strategy on JS:
   *
   * - We use [[java.security.SecureRandom]]
   *   directly, which is provided by the
   *   [scalajs-java-securerandom](https://github.com/scala-js/scala-js-java-securerandom)
   *   project. It detects the JS runtime,
   *   and uses the appropriate API (either
   *   `crypto.getRandomValues` or the Node.js
   *   `crypto` module).
   */
  def mkNew(): OsRng = {
    new JsRng
  }
}

private final class JsRng
  extends AdaptedOsRng(new JSecureRandom())
