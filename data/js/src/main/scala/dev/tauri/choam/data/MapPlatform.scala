/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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
package data

import cats.kernel.Hash
import dev.tauri.choam.Axn

private[data] abstract class MapPlatform extends AbstractMapPlatform {

  final override def simpleHashMap[K: Hash, V]: Axn[Map.Extra[K, V]] =
    SimpleMap[K, V]

  final override def hashMap[K: Hash, V]: Axn[Map[K, V]] =
    this.simpleHashMap[K, V]

  private[data] final override def unsafeSnapshot[F[_], K, V](m: Map[K, V])(implicit F: Reactive[F]) = {
    m match {
      case m: SimpleMap[_, _] =>
        m.unsafeSnapshot.run[F]
      case x =>
        throw new IllegalArgumentException(s"unexpected Map: ${x.getClass().getName()}")
    }
  }
}
