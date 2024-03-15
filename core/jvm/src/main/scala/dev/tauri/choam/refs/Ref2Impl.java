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

interface Ref2Impl<A, B> {
  B unsafeGetVolatile2();
  B unsafeGetPlain2();
  boolean unsafeCasVolatile2(B ov, B nv);
  B unsafeCmpxchgVolatile2(B ov, B nv);
  void unsafeSetVolatile2(B nv);
  void unsafeSetPlain2(B nv);
  long unsafeGetVersionVolatile2();
  long unsafeCmpxchgVersionVolatile2(long ov, long nv);
  WeakReference<Object> unsafeGetMarkerVolatile2();
  boolean unsafeCasMarkerVolatile2(WeakReference<Object> ov, WeakReference<Object> nv);
  long id1();
  long dummyImpl2(long v);
}
