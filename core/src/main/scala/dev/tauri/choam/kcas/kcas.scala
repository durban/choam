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

  private[choam] lazy val MCAS: KCAS =
    kcas.MCAS

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
    case fqns.MCAS =>
      MCAS
    case fqns.EMCAS =>
      EMCAS
    case _ =>
      throw new IllegalArgumentException(fqn)
  }

  object fqns {
    final val NaiveKCAS =
      "dev.tauri.choam.kcas.NaiveKCAS"
    final val MCAS =
      "dev.tauri.choam.kcas.MCAS"
    final val EMCAS =
      "dev.tauri.choam.kcas.EMCAS"
  }
}

// TODO: eliminate this (or preserve only as an impl detail of CASN)

/** CAS descriptor */
private[choam] sealed case class CASD[A](ref: Ref[A], ov: A, nv: A) {

  private[kcas] final def unsafeTryPerformOne(): Boolean =
    ref.unsafeTryPerformCas(ov, nv)

  final override def equals(that: Any): Boolean = that match {
    case CASD(tref, tov, tnv) =>
      (ref eq tref) && equ(ov, tov) && equ(nv, tnv)
    case _ =>
      false
  }

  final override def hashCode: Int =
    ref.## ^ System.identityHashCode(ov) ^ System.identityHashCode(nv)
}

private[choam] object CASD {

  val refOrdering: Ordering[Ref[_]] = new Ordering[Ref[_]] {
    override def compare(x: Ref[_], y: Ref[_]): Int = {
      Ref.globalCompare(x, y)
    }
  }

  def sortDescriptors(ds: List[CASD[_]]): List[CASD[_]] = {
    val s = new scala.collection.mutable.TreeMap[Ref[_], CASD[_]]()(refOrdering)
    for (d <- ds) {
      s.get(d.ref) match {
        case Some(other) =>
          assert(d.ref eq other.ref)
          KCAS.impossibleKCAS(d.ref, d.ov, d.nv, other.ov, other.nv)
        case None =>
          s.put(d.ref, d)
      }
    }
    s.values.toList
  }
}
