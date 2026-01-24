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

package dev.tauri.choam.internal.refs;

abstract class RefIdAndPadding<A> extends Padding<A> {

  private final long _id;

  RefIdAndPadding(long i) {
    this._id = i;
  }

  public final long id() {
    return this._id;
  }

  @Override
  public final int hashCode() {
    // `RefIdGen` generates IDs with
    // Fibonacci hashing, so no need
    // to hash them here even further.
    // IDs are globally unique, so the
    // default `equals` (based on object
    // identity) is fine for us.
    return (int) this._id;
  }
}
