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

package dev.tauri.choam.internal.mcas.emcas;

import dev.tauri.choam.internal.mcas.Version;
import dev.tauri.choam.internal.mcas.McasStatus;

/**
 * The status of an EMCAS-operation (and its
 * descriptor) has the following values and
 * transitions:
 *
 *      McasStatus.Active
 *             /|\
 *            / | \
 *           /  |  \
 *          /   |   \
 *         ↓    |    ↓
 *   FailedVal  |   any `v` where `EmcasStatus.isSuccessful(v)`
 *              ↓
 *        CycleDetected
 *
 * For successful operations, we store the new version
 * in the status field of the `EmcasDescriptor`, because
 * when we leave `EmcasWordDesc`s in the refs, the
 * new version must be accessible when later we use those
 * refs. (We don't write back versions immediately, only
 * later when we replace the `EmcasWordDesc` with the
 * final value.)
 *
 * FailedVal means the operation failed due to an old value
 * (or its version) not matching.
 *
 * CycleDetected means the operation failed due to a cycle
 * being detected when recursively helping. This can only
 * happen if `installRo = false` which is a "fast path",
 * but not certain to succeed/fail; it can get into a cycle.
 * This is not a "real" failure (although the descriptor will
 * be finalized), and it doesn't appear as a failed operation
 * on the public API. The solution to this failure is to
 * immediately retry the op with `installRo = true`, which
 * can never get into a cycle.
 */
final class EmcasStatus {

  /** Not really a status, used to break from `tryWord` */
  static final long Break = Version.None;

  /** Used to signal that a cycle was detected, need to retry with fallback */
  static final long CycleDetected = Version.Reserved;

  private EmcasStatus() {
    throw new UnsupportedOperationException();
  }

  static final boolean isSuccessful(long s) {
    return Version.isValid(s);
  }
}
