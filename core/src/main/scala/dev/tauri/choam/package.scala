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

package dev.tauri

package object choam {

  private[choam] type tailrec = scala.annotation.tailrec

  private[choam] type unused = scala.annotation.unused

  private[choam] type nowarn = scala.annotation.nowarn

  // Note: using these always leaves a check for
  // the package object in the bytecode (getstatic
  // and a null check). However, microbenchmarks
  // couldn't show a difference between these methods
  // and Java static method equivalents (see StaticsBench).

  @inline
  private[this] def box[A](a: A): AnyRef =
    a.asInstanceOf[AnyRef]

  @inline
  private[choam] def equ[A](x: A, y: A): Boolean =
    box(x) eq box(y)

  @inline
  private[choam] def isNull[A](a: A): Boolean =
    box(a) eq null

  @inline
  private[choam] def nullOf[A]: A =
    null.asInstanceOf[A]

  // TODO: maybe AssertionError?
  private[choam] def impossible(s: String): Nothing =
    throw new IllegalStateException(s)
}
