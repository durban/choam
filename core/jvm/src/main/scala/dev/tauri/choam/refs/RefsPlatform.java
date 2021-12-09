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

package dev.tauri.choam.refs;

import dev.tauri.choam.Ref;

final class RefsPlatform {

  private RefsPlatform() {}

  static final <A> Ref<A> unsafeNewRefU1(A initial, long i0, long i1, long i2, long i3) {
    return new RefU1<A>(initial, i0, i1, i2, i3);
  }

  static final <A> Ref<A> unsafeNewRefP1(A initial, long i0, long i1, long i2, long i3) {
    return new RefP1<A>(initial, i0, i1, i2, i3);
  }
}
