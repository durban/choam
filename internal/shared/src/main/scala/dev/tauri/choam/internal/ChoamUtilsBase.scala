/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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

private[choam] abstract class ChoamUtilsBase extends ChoamUtilsBasePlatform {

  private[choam] final type tailrec = scala.annotation.tailrec

  private[choam] final type switch = scala.annotation.switch

  private[choam] final type unused = scala.annotation.unused

  private[choam] final type nowarn = scala.annotation.nowarn

  @inline
  private[choam] final def box[A](a: A): AnyRef =
    a.asInstanceOf[AnyRef]

  @inline
  private[choam] final def equ[A](x: A, y: A): Boolean =
    box(x) eq box(y)

  @inline
  private[choam] final def nullOf[A]: A =
    null.asInstanceOf[A]

  @inline
  private[choam] final def isNull[A](a: A): Boolean =
    box(a) eq null

  /** @see Rxn.unsafe.impossibleRxn */
  @inline
  private[choam] final def impossible(s: String): Nothing =
    throw new AssertionError(s)
}
