/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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
  A unsafeGetVolatile1();
  A unsafeGetPlain1();
  boolean unsafeCasVolatile1(A ov, A nv);
  A unsafeCmpxchgVolatile1(A ov, A nv);
  void unsafeSetVolatile1(A nv);
  void unsafeSetPlain1(A nv);
  WeakReference<Object> unsafeGetMarkerVolatile1();
  boolean unsafeCasMarkerVolatile1(WeakReference<Object> ov, WeakReference<Object> nv);
  long id0();
  long id1();
  long id2();
  long id3();
  long dummyImpl1(long v);
}
