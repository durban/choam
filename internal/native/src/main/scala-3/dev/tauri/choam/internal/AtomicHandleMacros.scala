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

import scala.quoted.{ Quotes, Expr, Type }

import scala.scalanative.runtime.{ fromRawPtr, Intrinsics }

private[choam] object AtomicHandleMacros { // TODO: check the type of the field (like in Scala 2)

  final def applyImpl[O <: AnyRef : Type, H : Type](obj: Expr[O], fieldName: Expr[String])(using Quotes): Expr[AtomicHandle[H]] = {
    '{
      AtomicHandle.newAtomicHandleDoNotCallThisMethod[H](
        fromRawPtr[AnyRef](Intrinsics.classFieldRawPtr[O]($obj, $fieldName))
      )
    }
  }

  final def longImpl[O <: AnyRef : Type](obj: Expr[O], fieldName: Expr[String])(using Quotes): Expr[AtomicLongHandle] = {
    '{
      AtomicLongHandle.newAtomicLongHandleDoNotCallThisMethod(
        fromRawPtr[Long](Intrinsics.classFieldRawPtr[O]($obj, $fieldName))
      )
    }
  }

  final def intImpl[O <: AnyRef : Type](obj: Expr[O], fieldName: Expr[String])(using Quotes): Expr[AtomicIntHandle] = {
    '{
      AtomicIntHandle.newAtomicIntHandleDoNotCallThisMethod(
        fromRawPtr[Int](Intrinsics.classFieldRawPtr[O]($obj, $fieldName))
      )
    }
  }
}
