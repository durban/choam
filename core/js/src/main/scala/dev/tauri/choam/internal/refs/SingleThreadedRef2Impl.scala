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
package internal
package refs

import core.{ Ref, UnsealedRef2 }

private final class SingleThreadedRef2Impl[A, B](a: A, b: B)(
  i0: Long,
  i1: Long,
) extends UnsealedRef2[A, B] {

  final override val _1: Ref[A] =
    new SingleThreadedRefImpl(a)(i0)

  final override val _2: Ref[B] =
    new SingleThreadedRefImpl(b)(i1)

  final override def toString: String =
    refStringFrom2(i0, i1)
}
