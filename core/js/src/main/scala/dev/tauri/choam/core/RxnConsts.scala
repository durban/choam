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
package core

// Note: this class/object is duplicated for JVM/JS
private[core] object RxnConsts {
  @inline private[core] final val ContAndThen = 0.toByte
  @inline private[core] final val ContAndAlso = 1.toByte
  @inline private[core] final val ContAndAlsoJoin = 2.toByte
  @inline private[core] final val ContTailRecM = 3.toByte
  @inline private[core] final val ContPostCommit = 4.toByte
  @inline private[core] final val ContAfterPostCommit = 5.toByte // TODO: rename?
  @inline private[core] final val ContCommitPostCommit = 6.toByte
  @inline private[core] final val ContUpdWith = 7.toByte
  @inline private[core] final val ContAs = 8.toByte
  @inline private[core] final val ContProductR = 9.toByte
  @inline private[core] final val ContFlatMapF = 10.toByte
  @inline private[core] final val ContFlatMap = 11.toByte
  @inline private[core] final val ContMap = 12.toByte
  @inline private[core] final val ContMap2Right = 13.toByte
  @inline private[core] final val ContMap2Func = 14.toByte
  @inline private[core] final val ContOrElse = 15.toByte
  @inline private[core] final val ContEmbedAxn = 16.toByte
}
