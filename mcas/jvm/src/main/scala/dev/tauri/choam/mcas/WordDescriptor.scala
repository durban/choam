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
package mcas

private final class WordDescriptor[A] private (
  val half: HalfWordDescriptor[A],
  val parent: EMCASDescriptor,
) extends WordDescriptorBase {

  /**
   * The descriptor which was replaced by this
   * descriptor (because, at that time, it was
   * still in use).
   */
  def predecessor: WordDescriptor[_] =
    this.getPredecessorVolatile().asInstanceOf[WordDescriptor[_]]

  def casPredecessor(ov: WordDescriptor[_], nv: WordDescriptor[_]): Boolean =
    this.casPredecessorVolatile(ov, nv)

  def address: MemoryLocation[A] =
    this.half.address

  def ov: A =
    this.half.ov

  def nv: A =
    this.half.nv

  final def cast[B]: WordDescriptor[B] =
    this.asInstanceOf[WordDescriptor[B]]

  final def castToData: A =
    this.asInstanceOf[A]

  final override def toString: String =
    s"WordDescriptor(${this.address}, ${this.ov}, ${this.nv})"

  final def finalizeWd(): Unit = {
    // TODO: maybe `release`?
    this.setStrongRefOpaque(null)
  }

  // TODO: remove this
  private[this] def assertInvariants(wm: AnyRef): Unit = {
    this.getStrongRefPlain() match {
      case null => ()
      case sm => assert(sm eq wm)
    }
  }

  private[this] def getWeakMarker(): AnyRef = {
    this.get()
  }

  @tailrec
  final def isInUse(): Boolean = {
    val wm = this.getWeakMarker()
    this.assertInvariants(wm)
    (wm ne null) || {
      // TODO: maybe cut links here if we can?
      val pred = this.predecessor
      if (pred ne null) pred.cast[A].isInUse()
      else false
    }
  }

  final def tryHold(): AnyRef = {
    val wm = this.getWeakMarker()
    this.assertInvariants(wm)
    wm
  }
}

private object WordDescriptor {

  private[mcas] def apply[A](
    half: HalfWordDescriptor[A],
    parent: EMCASDescriptor,
  ): WordDescriptor[A] = new WordDescriptor[A](half, parent)

  def prepare[A](
    half: HalfWordDescriptor[A],
    parent: EMCASDescriptor,
  ): WordDescriptor[A] = WordDescriptor(half, parent)
}
