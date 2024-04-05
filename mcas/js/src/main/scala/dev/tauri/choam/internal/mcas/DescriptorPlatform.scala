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

package dev.tauri.choam
package internal
package mcas

private[mcas] abstract class DescriptorPlatform {

  protected def map: LogMap2[Any]

  protected def versionCas: LogEntry[java.lang.Long]

  final def hwdIterator(@unused ctx: Mcas.ThreadContext): Iterator[LogEntry[Any]] = {
    val wordsItr = this.map.toArray(()).iterator
    val vc = this.versionCas
    if (vc eq null) {
      wordsItr
    } else {
      Iterator.single(vc.cast[Any]).concat(wordsItr)
    }
  }
}
