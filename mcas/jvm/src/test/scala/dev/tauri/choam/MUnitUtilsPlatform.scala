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

import java.util.concurrent.atomic.AtomicLongFieldUpdater

import scala.util.control.NonFatal

trait MUnitUtilsPlatform extends MUnitUtilsScalaVer {

  private[this] lazy val _isGraal: Boolean = {
    try {
      Class.forName("org.graalvm.word.WordFactory")
      true
    } catch {
      case ex if NonFatal(ex) =>
        false
    }
  }

  final def platform: MUnitUtils.Platform =
    MUnitUtils.Jvm

  final def isVmSupportsLongCas(): Boolean = {
    val u = AtomicLongFieldUpdater.newUpdater(classOf[ClassWithLongField], "field")
    val n = u.getClass().getName()
    if (n.endsWith("CASUpdater")) true
    else if (n.endsWith("LockedUpdater")) false
    else throw new Exception(s"we don't know (name is '${n}')")
  }

  final def getJvmVersion(): Int = {
    Runtime.version().feature()
  }

  final def isGraal(): Boolean = {
    this._isGraal
  }
}
