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

package dev.tauri.choam.internal.refs;

import java.lang.ref.WeakReference;

interface Ref2Impl<A, B> {
  B unsafeGet2V();
  B unsafeGet2P();
  boolean unsafeCas2V(B ov, B nv);
  B unsafeCmpxchg2V(B ov, B nv);
  B unsafeCmpxchg2R(B ov, B nv);
  void unsafeSet2V(B nv);
  void unsafeSet2P(B nv);
  long unsafeGetVersion2V();
  long unsafeCmpxchgVersion2V(long ov, long nv);
  WeakReference<Object> unsafeGetMarker2V();
  boolean unsafeCasMarker2V(WeakReference<Object> ov, WeakReference<Object> nv);
  WeakReference<Object> unsafeCmpxchgMarker2R(WeakReference<Object> ov, WeakReference<Object> nv);
  long id1();
  long dummyImpl2(byte v);
}
