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

package dev.tauri.choam.internal.mcas;

// Note: this class/object is duplicated for JVM/JS
public final class Version {

  /** The initial version of every ref */
  public static final long Start = Long.MIN_VALUE;

  public static final Long BoxedStart = Long.valueOf(Start);

  public static final long Incr = 1L;

  /** An invalid version constant */
  public static final long None = Long.MAX_VALUE;

  static final long Active = None - 1L;
  static final long Successful = None - 2L;
  static final long FailedVal = None - 3L;
  public static final long Reserved = None - 4L;
  // FailedVer = any valid version

  /**
   * @return true, iff `ver` is a "real" version, and
   *         not a constant with a special meaning.
   */
  public static final boolean isValid(long ver) {
    return (ver >= Start) && (ver < Reserved);
  }

  private Version() {
    throw new UnsupportedOperationException();
  }
}
