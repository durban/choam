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

import scala.reflect.macros.whitebox.Context

private[choam] object CompileTimeSystemPropertyMacros {

  final def impl(c: Context)(name: c.Expr[String]): c.Expr[Boolean] = {

    import c.universe._

    val macroPos = c.macroApplication.pos
    name match {
      case Expr(Literal(Constant(propName: String))) =>
        val result = java.lang.Boolean.getBoolean(propName)
        c.Expr(Literal(Constant(result)))
      case x =>
        c.abort(macroPos, s"name has to be a literal String, got: ${showRaw(x)}")
    }
  }
}
