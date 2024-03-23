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

package dev.tauri.choam.internal.helpers;

import scala.collection.Iterator;

import dev.tauri.choam.internal.mcas.LogMap;
import dev.tauri.choam.internal.mcas.LogMap$;
import dev.tauri.choam.internal.mcas.HalfWordDescriptor;
import dev.tauri.choam.internal.mcas.HalfWordDescriptor$;
import dev.tauri.choam.internal.mcas.MemoryLocation;

public final class McasHelper {

  public static Object LogMap_empty() {
    return LogMap$.MODULE$.empty();
  }

  public static int LogMap_size(Object m) {
    return ((LogMap) m).size();
  }

  public static Iterator<HalfWordDescriptor<?>> LogMap_iterator(Object m) {
    return ((LogMap) m).valuesIterator();
  }

  public static HalfWordDescriptor<Object> LogMap_getOrElse(Object m, MemoryLocation<Object> k, HalfWordDescriptor<Object> d) {
    return ((LogMap) m).getOrElse(k, d);
  }

  public static Object LogMap_inserted(Object m, HalfWordDescriptor<Object> hwd) {
    return ((LogMap) m).inserted(hwd);
  }

  public static Object LogMap_updated(Object m, HalfWordDescriptor<Object> hwd) {
    return ((LogMap) m).updated(hwd);
  }

  public static <A> HalfWordDescriptor<A> newHwd(
    MemoryLocation<A> address,
    A ov,
    A nv,
    long version
  ) {
    return HalfWordDescriptor$.MODULE$.apply(address, ov, nv, version);
  }
}
