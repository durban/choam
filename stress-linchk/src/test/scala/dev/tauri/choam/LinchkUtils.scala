/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import scala.util.control.NonFatal
import scala.collection.concurrent.TrieMap

import org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedStrategyGuarantee
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions

trait LinchkUtils {

  /**
   * We need this to see fatal errors thrown in a forked JVM during a test (which
   * tend to happen with the bytecode-rewriting when using `ModelCheckingOptions`).
   */
  def printFatalErrors[A](block: => A): A = {
    try {
      block
    } catch {
      case ex if !NonFatal(ex) =>
        try { ex.printStackTrace() } catch { case _: Throwable => () }
        throw ex
    }
  }

  def defaultModelCheckingOptions(): ModelCheckingOptions = {
    def assumedAtomicPred(fullClassName: String): Boolean = {
      (fullClassName == classOf[TrieMap[_, _]].getName()) || fullClassName.startsWith("scala.collection.immutable.")
    }
    val assumedAtomic: ManagedStrategyGuarantee = {
      new ManagedStrategyGuarantee.MethodBuilder(KotlinFromScala.function1(assumedAtomicPred _))
        .allMethods()
        .treatAsAtomic()
    }
    def ignoredPred(fullClassName: String): Boolean = {
      fullClassName.startsWith("scala.Predef")
    }
    val ignored: ManagedStrategyGuarantee = {
      new ManagedStrategyGuarantee.MethodBuilder(KotlinFromScala.function1(ignoredPred _))
        .allMethods()
        .ignore()
    }

    new ModelCheckingOptions().addGuarantee(assumedAtomic).addGuarantee(ignored)
  }
}
