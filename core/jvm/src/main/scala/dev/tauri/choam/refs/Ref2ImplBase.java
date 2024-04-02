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

package dev.tauri.choam.refs;

import java.lang.ref.WeakReference;

interface Ref2ImplBase<A, B> {
  A unsafeGet1V();
  A unsafeGet1P();
  boolean unsafeCas1V(A ov, A nv);
  A unsafeCmpxchg1V(A ov, A nv);
  A unsafeCmpxchg1R(A ov, A nv);
  void unsafeSet1V(A nv);
  void unsafeSet1P(A nv);
  long unsafeGetVersion1V();
  long unsafeCmpxchgVersion1V(long ov, long nv);
  WeakReference<Object> unsafeGetMarker1V();
  boolean unsafeCasMarker1V(WeakReference<Object> ov, WeakReference<Object> nv);
  long id0();
  long dummyImpl1(byte v);
}
