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

package dev.tauri.choam
package internal
package mcas

final class DescriptorSpec_DefaultMcas
  extends DescriptorSpec
  with SpecDefaultMcas

trait DescriptorSpec extends BaseSpec { this: McasImplSpec =>

  test("Descriptor.mergeReads") {
    val r1 = MemoryLocation.unsafe("r1")
    val r2 = MemoryLocation.unsafe("r2")
    val r3 = MemoryLocation.unsafe("r3")
    val r4 = MemoryLocation.unsafe("r4")
    val r5 = MemoryLocation.unsafe("r5x")
    val r6 = MemoryLocation.unsafe("r6")
    val r7 = MemoryLocation.unsafe("r7")
    val ctx = this.mcasImpl.currentContext()
    val desc: AbstractDescriptor = ctx
      .builder()
      .readRef(r1)
      .readRef(r2)
      .updateRef[String](r3, s => s + "foo")
      .updateRef[String](r4, s => s + "foo")
      .result()
    val v0 = desc.validTs
    val snap: Descriptor = ctx.snapshot(desc)
    // simulating concurrent ops:
    assert(ctx.tryPerformSingleCas(r7, "r7", "R7")) // completely unrelated
    assert(ctx.tryPerformSingleCas(r5, "r5x", "r5")) // not yet in the log
    val v1 = v0 + 2L
    // simulating a sub-txn (`orElse`):
    val subDesc: AbstractDescriptor = ctx
      .builder(snap)
      .updateRef[String](r2, s => s + "bar")
      .updateRef[String](r4, s => s + "bar")
      .readRef(r5)
      .updateRef[String](r6, s => s + "bar")
      .result()
    // simulating abandoning the sub-txn (going to the other side of the `orElse`):
    val merged = Descriptor.mergeReadsInto(snap, subDesc)
    assertEquals(merged.size, 6)
    assertEquals(merged.validTs, v1)
    val hwd1 = merged.getOrElseNull(r1)
    assertEquals(hwd1.ov, "r1")
    assertEquals(hwd1.nv, "r1")
    assertEquals(hwd1.version, v0)
    val hwd2 = merged.getOrElseNull(r2)
    assertEquals(hwd2.ov, "r2")
    assertEquals(hwd2.nv, "r2")
    assertEquals(hwd2.version, v0)
    val hwd3 = merged.getOrElseNull(r3)
    assertEquals(hwd3.ov, "r3")
    assertEquals(hwd3.nv, "r3foo")
    assertEquals(hwd3.version, v0)
    val hwd4 = merged.getOrElseNull(r4)
    assertEquals(hwd4.ov, "r4")
    assertEquals(hwd4.nv, "r4foo")
    assertEquals(hwd4.version, v0)
    val hwd5 = merged.getOrElseNull(r5)
    assertEquals(hwd5.ov, "r5")
    assertEquals(hwd5.nv, "r5")
    assertEquals(hwd5.version, v1)
    val hwd6 = merged.getOrElseNull(r6)
    assertEquals(hwd6.ov, "r6")
    assertEquals(hwd6.nv, "r6")
    assertEquals(hwd6.version, v0)
    assertEquals(merged.getOrElseNull(r7), null)
  }
}
