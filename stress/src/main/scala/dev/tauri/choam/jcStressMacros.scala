/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.annotation.StaticAnnotation
import scala.reflect.macros.whitebox.Context

final class KCASParams(private val desc: String, private val disable: Boolean = false) extends StaticAnnotation {
  def macroTransform(annottees: Any*): Any =
    macro JcStressMacros.kcasParamsImpl
}

object JcStressMacros {

  def kcasParamsImpl(c: Context)(annottees: c.Expr[Any]*): c.Tree = {
    import c.universe._

    val impls = List(
      "CASN" -> q"_root_.dev.tauri.choam.kcas.KCAS.CASN",
      "MCAS" -> q"_root_.dev.tauri.choam.kcas.KCAS.MCAS",
      "EMCAS" -> q"_root_.dev.tauri.choam.kcas.KCAS.EMCAS",
      "NaiveKCAS" -> q"_root_.dev.tauri.choam.kcas.KCAS.NaiveKCAS"
    )

    val (baseDesc, disable) = c.prefix.tree match {
      case q"new $_($d)" =>
        (c.eval[String](c.Expr(d)), false)
      case q"new $_($d, $disable)" =>
        (c.eval[String](c.Expr(d)), c.eval[Boolean](c.Expr(disable)))
      case _ => c.abort(c.enclosingPosition, "Invalid macro argument")
    }

    def isMarked(mods: Modifiers): Boolean = {
      mods.annotations.find {
        case q"new _root_.org.openjdk.jcstress.annotations.Actor()" =>
          true
        case q"new Actor()" => // FIXME
          true
        case q"new _root_.org.openjdk.jcstress.annotations.Arbiter()" =>
          true
        case q"new Arbiter()" => // FIXME
          true
        case _ =>
          false
      }.isDefined
    }

    def mkOverrides(baseBody: List[c.Tree]): List[c.Tree] = {
      baseBody.collect {
        case DefDef(mods, name, tparams, params, ret, _) if isMarked(mods) =>
          val newMods = mods match {
            case Modifiers(flags, nme, anns) =>
              Modifiers(flags | Flag.OVERRIDE, nme, anns)
            case _ =>
              c.abort(c.enclosingPosition, "Invalid modifiers")
          }
          val ps = params.map(_.map {
            case ValDef(_, nme, _, _) => nme
            case _ => c.abort(c.enclosingPosition, "Invalid paramlist")
          })
          val newBody = q"""
            super.${name}[..$tparams](...$ps)
          """
          DefDef(newMods, name, tparams, params, ret, newBody)
      }
    }

    def mkTestClass(base: TypeName, overrides: List[c.Tree], kcasImpl: c.Tree, kcasName: String): c.Tree = {
      val prefix = base.toString().split('.').last
      val desc: String = s"${baseDesc} (${kcasName})"
      val clsName = TypeName(prefix + kcasName)
      val defi = q"""
        @_root_.org.openjdk.jcstress.annotations.State
        @_root_.org.openjdk.jcstress.annotations.Description($desc)
        class ${clsName} extends $base($kcasImpl) {
          ..$overrides
        }
      """
      if (disable) {
        defi
      } else {
        defi match {
          case ClassDef(mods, name, bs, tmpl) =>
            ClassDef(
              mods.mapAnnotations(_ :+ q"new _root_.org.openjdk.jcstress.annotations.JCStressTest()"),
              name,
              bs,
              tmpl
            )
          case _ =>
            c.abort(c.enclosingPosition, "Internal error")
        }
      }
    }

    def mkSubs(base: TypeName, baseBody: List[c.Tree]): List[c.Tree] = {
      val overrides = mkOverrides(baseBody)
      impls.map {
        case (kcasName, kcasImpl) =>
          mkTestClass(
            base,
            overrides,
            kcasImpl,
            kcasName
          )
      }
    }

    def transformBaseClass(cls: ClassDef): ClassDef = {
      val paramName: TermName = cls match {
        case q"$_ class $_[..$_] $_(...$paramss) extends { ..$_ } with ..$_ { $_ => ..$_ }" =>
          paramss match {
            case (ValDef(_, paramName, _, _) :: _) :: _ =>
              paramName
            case _ =>
              c.abort(c.enclosingPosition, s"Expected at least one constructor parameter")
          }
        case _ =>
          c.abort(c.enclosingPosition, s"Expected a class definition, got ${showRaw(cls)}")
      }
      val kcasImplDef: Tree = q"""
        protected implicit final val kcasImpl: _root_.dev.tauri.choam.kcas.KCAS =
          ${paramName}
      """
      cls match {
        case ClassDef(mods, name, tparams, Template(parents, self, body)) =>
          val newBody = body :+ kcasImplDef
          ClassDef(mods, name, tparams, Template(parents, self, newBody))
        case _ =>
          c.abort(c.enclosingPosition, s"Expected a class definition, got ${showRaw(cls)}")
      }
    }

    annottees.map(_.tree).toList match {
      case List(cls @ ClassDef(_, name, _, Template(_, _, body))) =>
        q"""
          ${transformBaseClass(cls)}

          object ${name.toTermName} {
            ..${mkSubs(name, body)}
          }
        """

      case List(cls @ ClassDef(_, name, _, Template(_, _, body)), ModuleDef(mds, nme, Template(ps, slf, bdy))) =>
        val newTemplate = Template(ps, slf, bdy ++ mkSubs(name, body))
        val newMod = ModuleDef(mds, nme, newTemplate)
        q"""
          ${transformBaseClass(cls)}
          ${newMod}
        """

      case h :: _ =>
        c.abort(c.enclosingPosition, s"Invalid annotation target: ${h} (${h.getClass.getName})")

      case _ =>
        c.abort(c.enclosingPosition, "Invalid annotation target")
    }
  }
}
