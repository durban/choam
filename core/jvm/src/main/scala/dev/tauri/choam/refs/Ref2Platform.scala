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
package refs

import java.util.concurrent.ThreadLocalRandom

private[refs] abstract class Ref2Platform {

  final def unsafeP1P1[A, B](a: A, b: B): refs.Ref2[A, B] = {
    val tlr = ThreadLocalRandom.current()
    new refs.RefP1P1[A, B](
      a,
      b,
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong()
    )
  }

  final def unsafeP2[A, B](a: A, b: B): refs.Ref2[A, B] = {
    val tlr = ThreadLocalRandom.current()
    new refs.RefP2[A, B](
      a,
      b,
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong(),
      tlr.nextLong()
    )
  }
}
