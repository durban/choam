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

package dev.tauri.choam.internal.mcas.emcas;

import dev.tauri.choam.internal.mcas.Version;
import dev.tauri.choam.internal.mcas.McasStatus;

/**
 * The status of an EMCAS-operation (and its
 * descriptor) has the following values and
 * transitions:
 *
 *   McasStatus.Active
 *          /\
 *         /  \
 *        /    \
 *       /      \
 *      ↓        ↓
 *   FailedVal  any `v` where `EmcasStatus.isSuccessful(v)`
 *
 * For successful operations, we store the new version
 * in the status field of the `EmcasDescriptor`, because
 * when we leave `WordDescriptor`s in the refs, the
 * new version must be accessible when later we use those
 * refs. (We don't write back versions immediately, only
 * later when we replace the `WordDescriptor` with the
 * final value.)
 */
final class EmcasStatus {

  /** Not really a status, used to break from `tryWord` */
  static final long Break = Version.None;

  private EmcasStatus() {
    throw new UnsupportedOperationException();
  }

  static final boolean isSuccessful(long s) {
    return Version.isValid(s);
  }

  static final String toString(long s) {
    if (s == McasStatus.Active) {
      return "ACTIVE";
    } else if (s == McasStatus.FailedVal) {
      return "FAILED";
    } else if (EmcasStatus.isSuccessful(s)) {
      return "SUCCESSFUL";
    } else {
      throw new IllegalArgumentException("invalid EMCAS status: " + Long.toString(s));
    }
  }
}
