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
    this.clearStrongRef()
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

  private final def isInUseDirectly(): Boolean = {
    this.getWeakMarker() ne null
  }

  final def isInUse(): Boolean = {
    if (this.isInUseDirectly()) {
      // we're in use, but we may still
      // be able to cut some predecessors:
      isInUseRecursive(curr = this.predecessor, lastUsed = this)
    } else {
      // we're not in use, but some
      // predecessors may be:
      isInUseRecursive(curr = this.predecessor, lastUsed = null)
    }
  }

  @tailrec
  private[this] final def isInUseRecursive(
    curr: WordDescriptor[_], // null if this is the end
    lastUsed: WordDescriptor[_], // null if no used was found so far
  ): Boolean = {
    if (curr eq null) { // end of the line
      if (lastUsed ne null) {
        lastUsed.clearPredecessor()
        true // there was at least one used
      } else {
        false // there was no used
      }
    } else if (curr.isInUseDirectly()) {
      isInUseRecursive(curr = curr.predecessor, lastUsed = curr)
    } else {
      isInUseRecursive(curr = curr.predecessor, lastUsed = lastUsed)
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
