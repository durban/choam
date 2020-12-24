/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

// TODO: detect impossible CAS-es
// TODO: support thread interruption in (some) retry loops
// TODO: think about exception safety (e.g., leaving behind descriptors)

/** Common interface for k-CAS implementations */
abstract class KCAS { self =>

  /**
   * Rules:
   * - no use after `tryPerform` or `cancel`
   * - must call `tryPerform` or `cancel` before releasing the reference
   */
  private[choam] trait Desc {
    final def impl: KCAS = self
    def withCAS[A](ref: Ref[A], ov: A, nv: A): Desc
    def snapshot(): Snap
    def tryPerform(): Boolean
    def cancel(): Unit
  }

  /**
   * Rules:
   * - mustn't `load` or `discard`, unless the original (which
   *   created the snapshot) is already finished (with `tryPerform`
   *   or `cancel`)
   */
  private[choam] trait Snap {
    def load(): Desc
    def discard(): Unit
  }

  private[choam] def start(): Desc

  private[choam] def tryReadOne[A](ref: Ref[A]): A

  @tailrec
  private[choam] final def read[A](ref: Ref[A]): A = {
    tryReadOne(ref) match {
      case null =>
        // TODO: Retrying on `null` is because of NaiveKCAS,
        // TODO: and should be removed from here.
        read(ref)
      case a =>
        a
    }
  }

  private[choam] def isNaive: Boolean =
    false
}

/** Provides various k-CAS implementations */
private[choam] object KCAS {

  private[choam] lazy val NaiveKCAS: KCAS =
    kcas.NaiveKCAS

  private[choam] lazy val EMCAS: KCAS =
    kcas.EMCAS

  private[kcas] def impossibleKCAS[A, B](ref: Ref[_], ova: A, nva: A, ovb: B, nvb: B): Nothing = {
    throw new IllegalArgumentException(
      s"Impossible k-CAS for ${ref}: ${ova} -> ${nva} and ${ovb} -> ${nvb}"
    )
  }

  def unsafeLookup(fqn: String): KCAS = fqn match {
    case fqns.NaiveKCAS =>
      NaiveKCAS
    case fqns.EMCAS =>
      EMCAS
    case _ =>
      throw new IllegalArgumentException(fqn)
  }

  object fqns {
    final val NaiveKCAS =
      "dev.tauri.choam.kcas.NaiveKCAS"
    final val EMCAS =
      "dev.tauri.choam.kcas.EMCAS"
  }
}
