/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package lcagent

import java.lang.instrument.{ ClassFileTransformer }

import org.objectweb.asm.{ ClassReader, ClassWriter, ClassVisitor, MethodVisitor, Opcodes, Type }
import org.objectweb.asm.commons.{ GeneratorAdapter, Method }

/**
 * This is a terrible idea (and we're not using it currently):
 * lincheck does some bytecode-rewriting to do model-checking.
 * Some of this rewriting made the bytecode of some Scala
 * classes invalid (e.g., probably `scala.Predef`), so we wanted
 * to exclude them from rewriting. But, the set of classes
 * affected by this rewriting was determined by a private method
 * `TransformationClassLoader#doNotTransform`. So we... had a
 * java agent, which transforms the bytecode of `TransformationClassLoader`,
 * and modifies the `doNotTransform` method, to include our
 * additional classes to not transform (see `TransformationClassLoaderOverrideImpl`).
 *
 * Since version 2.30, lincheck does the transformation in a different
 * way, so we (probably) don't need this any more (see `Premain`).
 * (And the current `TclTransformer` does nothing anyway, because
 * `TransformationClassLoader` doesn't exist any more.)
 *
 * For future reference, the set of excluded classes is now determined in
 * org/jetbrains/kotlinx/lincheck/transformation/LincheckJavaAgent.kt
 * by `internal object LincheckClassFileTransformer`.
 */
final class TclTransformer extends ClassFileTransformer { cTransformer =>

  final val asmApi = Opcodes.ASM9

  final val classToTransform = "org/jetbrains/kotlinx/lincheck/TransformationClassLoader"
  final val classToTransformType: Type = Type.getObjectType(classToTransform)
  final val methodToTransform = "doNotTransform"
  final val overrideClass: Type = Type.getObjectType("dev/tauri/choam/TransformationClassLoaderOverride")
  final val overrideMethod: Method = new Method(
    methodToTransform,
    Type.BOOLEAN_TYPE,
    Array(Type.getObjectType("java/lang/String"))
  )
  final val expectedMethodDesc = "(Ljava/lang/String;)Z"

  final override def transform(
    loader: ClassLoader,
    className: String,
    classBeingRedefined: Class[_],
    protectionDomain: java.security.ProtectionDomain,
    classfileBuffer: Array[Byte]
  ): Array[Byte] = {
    require(classBeingRedefined eq null)
    if (className == classToTransform) {
      try transformTcl(classfileBuffer) catch {
        case ex: Throwable =>
          ex.printStackTrace()
          throw ex
      }
    } else {
      null // no transform needed
    }
  }

  private[this] final def transformTcl(classfileBuffer: Array[Byte]): Array[Byte] = {
    val cr = new ClassReader(classfileBuffer)
    val cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)
    val visitor = tclVisitor(cw)
    cr.accept(visitor, ClassReader.EXPAND_FRAMES)
    cw.toByteArray()
  }

  private[this] def tclVisitor(cw: ClassWriter): ClassVisitor = new ClassVisitor(asmApi, cw) {

    override def visitMethod(
      access: Int,
      name: String,
      descriptor: String,
      signature: String,
      exceptions: Array[String]
    ): MethodVisitor = {
      if (name == methodToTransform) {
        assert(descriptor == expectedMethodDesc)
        new GeneratorAdapter(asmApi, super.visitMethod(access, name, descriptor, signature, exceptions), access, name, descriptor) {
          override def visitCode(): Unit = {
            // replace the body of `doNotTransform`
            // with essentially this: `{
            //   if (TransformationClassLoaderOverride.doNotTransform(className)) {
            //     return true;
            //   }
            //   // original body of `doNotTransform` ...
            // }`
            val originalCodeLabel = newLabel()
            loadArg(0)
            invokeStatic(overrideClass, overrideMethod)
            ifZCmp(GeneratorAdapter.EQ, originalCodeLabel)
            push(1)
            returnValue()
            mark(originalCodeLabel)
            // original body ...
          }
        }
      } else {
        super.visitMethod(access, name, descriptor, signature, exceptions)
      }
    }
  }
}
