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
package kcas

import mcas.MemoryLocation

// TODO: detect impossible CAS-es
// TODO: support thread interruption in (some) retry loops

/** Common interface for k-CAS implementations */
abstract class KCAS { self =>

  private[choam] def currentContext(): ThreadContext

  private[choam] def start(@unused ctx: ThreadContext): HalfEMCASDescriptor =
    HalfEMCASDescriptor.empty

  private[choam] def addCas[A](desc: HalfEMCASDescriptor, ref: MemoryLocation[A], ov: A, nv: A, @unused ctx: ThreadContext): HalfEMCASDescriptor = {
    val wd = HalfWordDescriptor(ref, ov, nv)
    desc.add(wd)
  }

  private[choam] def addAll(to: HalfEMCASDescriptor, from: HalfEMCASDescriptor): HalfEMCASDescriptor = {
    val it = from.map.valuesIterator
    var res = to
    while (it.hasNext) {
      res = res.add(it.next())
    }
    res
  }

  private[choam] def snapshot(desc: HalfEMCASDescriptor, @unused ctx: ThreadContext): HalfEMCASDescriptor =
    desc

  private[choam] def tryPerform(desc: HalfEMCASDescriptor, ctx: ThreadContext): Boolean

  private[choam] def read[A](ref: MemoryLocation[A], ctx: ThreadContext): A

  /** Only for testing/benchmarking */
  private[choam] def printStatistics(@unused println: String => Unit): Unit =
    ()

  final def doSingleCas[A](ref: MemoryLocation[A], ov: A, nv: A, ctx: ThreadContext): Boolean = {
    val desc = this.addCas(this.start(ctx), ref, ov, nv, ctx)
    this.tryPerform(desc, ctx)
  }
}

/** Provides various k-CAS implementations */
private[choam] object KCAS {

  private[choam] lazy val NaiveKCAS: KCAS =
    kcas.NaiveKCAS

  private[choam] lazy val EMCAS: KCAS =
    kcas.EMCAS

  private[kcas] def impossibleKCAS[A, B](ref: MemoryLocation[_], ova: A, nva: A, ovb: B, nvb: B): Nothing = {
    throw new ImpossibleOperation(
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
