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

  final def hwdIterator(ctx: Mcas.ThreadContext): Iterator[LogEntry[Any]] = {
    require(ctx.impl ne Mcas.Emcas)
    // This is really not effective (we're making an
    // array of WDs, and mapping it back to HWDs), but
    // this is not EMCAS, so we don't really care:
    val wordsItr = this.map.toArray(null).map {
      case wd: EmcasWordDesc[_] =>
        LogEntry(wd.address.cast[Any], wd.ov, wd.nv, wd.oldVersion)
      case entry: LogEntry[_] =>
        entry
    }.iterator
    val vc = this.versionCas
    if (vc eq null) {
      wordsItr
    } else {
      Iterator.single(vc.cast[Any]).concat(wordsItr)
    }
  }

  /** This only exists on the JVM, and used by EMCAS instead of the above */
  final def toWdArray(parent: emcas.EmcasDescriptor): Array[WdLike[Any]] = {
    this.map.toArray(parent)
  }
}
