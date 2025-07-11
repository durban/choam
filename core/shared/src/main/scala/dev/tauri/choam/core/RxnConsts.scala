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

private object RxnConsts {
  final val ContAndThen = 0
  final val ContAndAlso = 1
  final val ContAndAlsoJoin = 2
  final val ContTailRecM = 3
  final val ContPostCommit = 4
  final val ContAfterPostCommit = 5 // TODO: rename?
  final val ContCommitPostCommit = 6
  final val ContUpdWith = 7
  final val ContAs = 8
  final val ContProductR = 9
  // 10 was ContFlatMapF
  final val ContFlatMap = 11
  final val ContMap = 12
  final val ContMap2Right = 13
  final val ContMap2Func = 14
  final val ContOrElse = 15
  final val ContFlatten = 16
  final val ContRegisterPostCommit = 17
}
