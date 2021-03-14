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

package dev.tauri.choam
package ref

import kcas.Ref

private final class Ref2Ref1[A, B](self: Ref2Impl[A, B]) extends Ref[A] {

  override def unsafeGet(): A =
    self.unsafeGet1()

  override def unsafeTryPerformCas(ov: A, nv: A): Boolean =
    self.unsafeTryPerformCas1(ov, nv)

  override def unsafeSet(a: A): Unit =
    self.unsafeSet1(a)

  override def id0: Long =
    self.id0

  override def id1: Long =
    self.id1

  override def id2: Long =
    self.id2

  override def id3: Long =
    self.id3

  override def dummy(v: Long): Long =
    self.dummyImpl(v)
}

private final class Ref2Ref2[A, B](self: Ref2Impl[A, B]) extends Ref[B] {

  override def unsafeGet(): B =
    self.unsafeGet2()

  override def unsafeTryPerformCas(ov: B, nv: B): Boolean =
    self.unsafeTryPerformCas2(ov, nv)

  override def unsafeSet(b: B): Unit =
    self.unsafeSet2(b)

  override def id0: Long =
    self.id4

  override def id1: Long =
    self.id5

  override def id2: Long =
    self.id6

  override def id3: Long =
    self.id7

  override def dummy(v: Long): Long =
    self.dummyImpl2(v)
}
