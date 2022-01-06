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
package refs

private object RefsPlatform {

  def unsafeNewRefU1[A](initial: A, i0: Long, i1: Long, i2: Long, i3: Long): Ref[A] =
    new SingleThreadedRefImpl[A](initial)(i0, i1, i2, i3)

  def unsafeNewRefP1[A](initial: A, i0: Long, i1: Long, i2: Long, i3: Long): Ref[A] =
    new SingleThreadedRefImpl[A](initial)(i0, i1, i2, i3)
}
