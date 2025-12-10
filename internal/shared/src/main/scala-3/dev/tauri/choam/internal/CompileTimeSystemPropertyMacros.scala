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

import scala.quoted.{ Quotes, Expr }

private[choam] object CompileTimeSystemPropertyMacros {

  final def implBoolean(name: Expr[String])(using Quotes): Expr[Boolean] = {
    val propName: String = name.valueOrAbort
    val result = java.lang.Boolean.getBoolean(propName)
    Expr[Boolean](result)
  }

  final def implString(name: Expr[String])(using Quotes): Expr[String] = {
    val propName: String = name.valueOrAbort
    val result = System.getProperty(propName)
    if (result ne null) {
      Expr[String](result)
    } else {
      '{null}
    }
  }
}
