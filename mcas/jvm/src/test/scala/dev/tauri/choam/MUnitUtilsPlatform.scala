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

import java.util.concurrent.atomic.AtomicLongFieldUpdater

trait MUnitUtilsPlatform {

  final def isJvm(): Boolean =
    true

  final def isJs(): Boolean =
    false

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
}
