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

public class VersionJ {

  public static final long Start = Long.MIN_VALUE; // Note: this is copied from Version.scala

  public static final long Active = Long.MAX_VALUE - 1L; // Note: this is copied from Version.scala

  public static final long Reserved = Long.MAX_VALUE - 4L; // Note: this is copied from Version.scala

  private VersionJ() {
    throw new UnsupportedOperationException();
  }
}
