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

package dev.tauri.choam;

class KotlinFromScala {

  private KotlinFromScala() {
    // this class should not be instantiated
  }

  public static <A, B> kotlin.jvm.functions.Function1<A, B> function1(scala.Function1<A, B> sf) {
    return new kotlin.jvm.functions.Function1<A, B>() {
      @Override
      public final B invoke(A a) {
        return sf.apply(a);
      }
    };
  }
}
