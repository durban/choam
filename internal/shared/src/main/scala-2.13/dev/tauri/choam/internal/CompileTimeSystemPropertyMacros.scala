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

import scala.reflect.macros.whitebox.Context

private[choam] object CompileTimeSystemPropertyMacros {

  final def implBoolean(c: Context)(name: c.Expr[String]): c.Expr[Boolean] = {
    import c.universe._
    val propName = this.getPropName(c)(name)
    val result = java.lang.Boolean.getBoolean(propName)
    c.Expr(Literal(Constant(result)))
  }

  final def implString(c: Context)(name: c.Expr[String]): c.Expr[String] = {
    import c.universe._
    val propName = this.getPropName(c)(name)
    val result = System.getProperty(propName)
    c.Expr(Literal(Constant(result)))
  }

  private[this] final def getPropName(c: Context)(name: c.Expr[String]): String = {
    import c.universe._
    name match {
      case Expr(Literal(Constant(propName: String))) =>
        propName
      case x =>
        c.abort(c.macroApplication.pos, s"name has to be a literal String, got: ${showRaw(x)}")
    }
  }
}
