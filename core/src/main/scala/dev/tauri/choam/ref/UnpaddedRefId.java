/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2021 Daniel Urban and contributors listed in NOTICE.txt
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

package dev.tauri.choam.ref;

abstract class UnpaddedRefId {

  private final long _id0;
  private final long _id1;
  private final long _id2;
  private final long _id3;

  UnpaddedRefId(long i0, long i1, long i2, long i3) {
    this._id0 = i0;
    this._id1 = i1;
    this._id2 = i2;
    this._id3 = i3;
  }

  public final long id0() {
    return this._id0;
  }

  public final long id1() {
    return this._id1;
  }

  public final long id2() {
    return this._id2;
  }

  public final long id3() {
    return this._id3;
  }

  @Override
  public final String toString() {
    return "Ref@" + Integer.toHexString(this.hashCode());
  }
}
