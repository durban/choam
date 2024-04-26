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

package dev.tauri.choam.core;

// Note: this class/object is duplicated for JVM/JS
class RxnConsts {
  static final byte ContAndThen = 0;
  static final byte ContAndAlso = 1;
  static final byte ContAndAlsoJoin = 2;
  static final byte ContTailRecM = 3;
  static final byte ContPostCommit = 4;
  static final byte ContAfterPostCommit = 5; // TODO: rename?
  static final byte ContCommitPostCommit = 6;
  static final byte ContUpdWith = 7;
  static final byte ContAs = 8;
  static final byte ContProductR = 9;
  static final byte ContFlatMapF = 10;
  static final byte ContFlatMap = 11;
}
