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

import scala.reflect.macros.blackbox.Context

private[choam] object AtomicHandleMacros {

  final def applyImpl[O <: AnyRef, H](c: Context)(
    obj: c.Expr[O],
    fieldName: c.Expr[String],
  )(implicit O: c.WeakTypeTag[O], H: c.WeakTypeTag[H]): c.Expr[AtomicHandle[H]] = {

    import c.universe._

    val macroPos = c.macroApplication.pos
    val anyRefTpe = typeOf[AnyRef]
    val handleTpe = H.tpe
    val (nme, fieldTpe) = commonChecks(c)(obj, fieldName)
    val erasedFieldTpe = fieldTpe.erasure
    if (erasedFieldTpe <:< anyRefTpe) {
      if (handleTpe =:= fieldTpe) {
        c.Expr[AtomicHandle[H]](
          q"""_root_.dev.tauri.choam.internal.AtomicHandle.newAtomicHandleDoNotCallThisMethod[$H](
            _root_.scala.scalanative.runtime.fromRawPtr[${anyRefTpe}](
              _root_.scala.scalanative.runtime.Intrinsics.classFieldRawPtr[$O]($obj, $fieldName)
            )
          )"""
        )
      } else {
        c.abort(macroPos, s"the type of the handle ${handleTpe} ≠ ${fieldTpe} (the type of the field)")
      }
    } else {
      c.abort(macroPos, s"field ${nme}: ${fieldTpe} looks like a primitive")
    }
  }

  final def longImpl[O <: AnyRef](c: Context)(
    obj: c.Expr[O],
    fieldName: c.Expr[String],
  )(implicit O: c.WeakTypeTag[O]): c.Expr[AtomicLongHandle] = {

    import c.universe._

    val macroPos = c.macroApplication.pos
    val longTpe = typeOf[Long]
    val (nme, fieldTpe) = commonChecks(c)(obj, fieldName)
    if (fieldTpe =:= longTpe) {
      c.Expr[AtomicLongHandle](
        q"""_root_.dev.tauri.choam.internal.AtomicLongHandle.newAtomicLongHandleDoNotCallThisMethod(
          _root_.scala.scalanative.runtime.fromRawPtr[${longTpe}](
            _root_.scala.scalanative.runtime.Intrinsics.classFieldRawPtr[$O]($obj, $fieldName)
          )
        )"""
      )
    } else {
      c.abort(macroPos, s"field ${nme}: ${fieldTpe} doesn't have type Long")
    }
  }

  private[this] final def commonChecks[O <: AnyRef, R <: AnyVal](c: Context)(
    obj: c.Expr[O],
    fieldName: c.Expr[String],
  ): (c.TermName, c.Type) = {

    import c.universe._

    val macroPos = c.macroApplication.pos
    fieldName match {
      case Expr(Literal(Constant(s: String))) =>
        val nme = c.universe.TermName(s)
        obj.actualType.decl(nme) match {
          case NoSymbol =>
            c.abort(macroPos, s"no such member: $nme")
          case sym: TermSymbol =>
            if (sym.isOverloaded) {
              c.abort(macroPos, s"member $nme is overloaded")
            } else if (!sym.isVar) {
              c.abort(macroPos, s"member $nme is not a var")
            } else {
              val fieldTpe = sym.infoIn(obj.actualType)
              (nme, fieldTpe)
            }
          case x =>
            c.abort(macroPos, s"expected a TermSymbol, got: ${showRaw(x)}")
        }
      case x =>
        c.abort(macroPos, s"the field name has to be a literal String, got: ${showRaw(x)}")
    }
  }
}
