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
package ref

import kcas.Ref

trait Ref2[A, B] {

  def _1: Ref[A]

  def _2: Ref[B]

  def consistentRead: Reaction[Unit, (A, B)] =
    React.consistentRead(this._1, this._2)
}

object Ref2 {

  def unapply[A, B](r: Ref2[A, B]): Some[(Ref[A], Ref[B])] =
    Some((r._1, r._2))
}
