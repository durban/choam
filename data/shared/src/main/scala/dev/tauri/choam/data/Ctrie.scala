/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2022 Daniel Urban and contributors listed in NOTICE.txt
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

import cats.{ Eq, Hash, Traverse }
import cats.instances.list._

import mcas.MCAS

/**
 * Concurrent hash trie, described in [Concurrent Tries with Efficient
 * Non-Blocking Snapshots](http://lampwww.epfl.ch/~prokopec/ctries-snapshot.pdf)
 * by Aleksandar Prokopec, Nathan G. Bronson, Phil Bagwell and Martin Odersky.
 */
private[choam] final class Ctrie[K, V] private ()(implicit hs: Hash[K]) {

  // TODO: Cache-trie:
  // TODO: http://aleksandar-prokopec.com/resources/docs/p137-prokopec.pdf
  // TODO: http://aleksandar-prokopec.com/resources/docs/cachetrie-remove.pdf

  // TODO: optimize (only compute hash code once, store hash code in trie)

  import Ctrie._

  private[this] val root: Ref[INode[K, V]] =
    Ref.unsafe(new INode(Ref.unsafe[MainNode[K, V]](new CNode(0, Array.empty)), Ref.unsafe(new Gen)))

  val lookup: Rxn[K, Option[V]] = {

    def ilookup(i: INode[K, V], k: K, lev: Int, @unused parent: INode[K, V]): Axn[V] = {
      for {
        im <- i.main.unsafeDirectRead
        v <- im match {
          case cn: CNode[K, V] =>
            val (flag, pos) = flagpos(hs.hash(k), lev, cn.bmp)
            if ((cn.bmp & flag) == 0) {
              Rxn.ret(NOTFOUND.as[V])
            } else {
              cn(pos) match {
                case sin: INode[K, V] =>
                  ilookup(sin, k, lev + W, i)
                case sn: SNode[K, V] =>
                  val r = if (hs.eqv(sn.k, k)) sn.v else NOTFOUND.as[V]
                  Rxn.ret(r)
              }
            }
          case _: TNode[K, V] =>
            // TODO: clean
            Rxn.unsafe.retry
          case ln: LNode[K, V] =>
            ln.lookup.provide(k)
        }
        _ <- i.main.unsafeCas(im, im) // FIXME: this is only to be composable
      } yield v
    }

    Rxn.computed[K, Option[V]] { k =>
      for {
        r <- root.unsafeDirectRead
        v <- ilookup(r, k, 0, null)
      } yield if (NOTFOUND.isNotFound(v)) None else Some(v)
    }
  }

  val insert: Rxn[(K, V), Unit] = {

    def iinsert(i: INode[K, V], k: K, v: V, lev: Int, @unused parent: INode[K, V]): Axn[Unit] = {
      for {
        im <- i.main.unsafeDirectRead
        gen <- i.gen.unsafeDirectRead
        _ <- im match {
          case cn: CNode[K, V] =>
            val (flag, pos) = flagpos(hs.hash(k), lev, cn.bmp)
            if ((cn.bmp & flag) == 0) {
              val ncn = cn.inserted(pos, flag, new SNode(k, v))
              i.main.unsafeCas(cn, ncn)
            } else {
              cn(pos) match {
                case sin: INode[K, V] =>
                  iinsert(sin, k, v, lev + W, i)
                case sn: SNode[K, V] =>
                  val ncn = if (!hs.eqv(sn.k, k)) {
                    val nsn = new SNode(k, v)
                    val nin = new INode(
                      Ref.unsafe[MainNode[K, V]](CNode.doubleton(sn, nsn, lev + W, gen)), Ref.unsafe(gen)
                    )
                    cn.updated(pos, nin)
                  } else {
                    cn.updated(pos, new SNode(k, v))
                  }
                  i.main.unsafeCas(cn, ncn)
              }
            }
          case _: TNode[K, V] =>
            // TODO: clean
            Rxn.unsafe.retry
          case ln: LNode[K, V] =>
            i.main.unsafeCas(ln, ln.inserted(k, v))
        }
      } yield ()
    }

    Rxn.computed[(K, V), Unit] {
      case (k, v) =>
        for {
          r <- root.unsafeDirectRead
          _ <- iinsert(r, k, v, 0, null)
        } yield ()
    }
  }

  // TODO: this is almost certainly not correct
  def remove: Rxn[K, Option[V]] = {

    def iremove(i: INode[K, V], k: K, lev: Int, parent: INode[K, V]): Axn[V] = {
      for {
        im <- i.main.unsafeDirectRead
        v <- im match {
          case cn: CNode[_, _] =>
            val (flag, pos) = flagpos(hs.hash(k), lev, cn.bmp)
            if ((cn.bmp & flag) == 0) {
              Rxn.pure(NOTFOUND.as[V])
            } else {
              val axn: Axn[V] = cn(pos) match {
                case sin: INode[_, _] =>
                  iremove(sin, k, lev + W, i)
                case sn: SNode[_, _] =>
                  if (!hs.eqv(sn.k, k)) {
                    Rxn.pure(NOTFOUND.as[V])
                  } else {
                    val ncn = cn.removed(pos, flag)
                    val cntr = ncn.toContracted(lev)
                    i.main.unsafeCas(im, cntr).as(sn.v)
                  }
              }
              axn.flatMap { v =>
                if (NOTFOUND.isNotFound(v)) {
                  // nothing to do
                  Rxn.pure(v)
                } else {
                  i.main.unsafeDirectRead.flatMap { (reRead: MainNode[K, V]) =>
                    if (reRead.isTomb) parent.cleanParent(i, k, lev - W).as(v)
                    else Rxn.pure(v)
                  }
                }
              }
            }
          case _: TNode[_, _] =>
            Rxn.unsafe.delayComputed(parent.clean(lev - W).as(Rxn.unsafe.retry))
          case ln: LNode[_, _] =>
            val nln = ln.removed(k)
            val nln2 = if (nln.isSingleton) nln.asSNode.entomb else nln
            i.main.unsafeCas(im, nln2).as(ln.get(k))
        }
      } yield v
    }

    Rxn.computed[K, Option[V]] { k =>
      root.unsafeDirectRead.flatMap { r =>
        iremove(r, k, lev = 0, parent = null).map { v =>
          if (NOTFOUND.isNotFound(v)) None else Some(v)
        }
      }
    }
  }

  /** Only call in quiescent states! */
  private[choam] def debugStr(kcas: MCAS): String = {
    debug.unsafePerform(0, kcas)
  }

  private[this] def debug: Rxn[Int, String] = Rxn.computed { level =>
    for {
      r <- root.unsafeDirectRead
      rs <- r.debug.provide(level)
    } yield rs
  }
}

private[choam] object Ctrie {

  def apply[K : Hash, V]: Axn[Ctrie[K, V]] = {
    Rxn.unsafe.delay { _ => Ctrie.unsafe[K, V] }
  }

  def unsafe[K : Hash, V]: Ctrie[K, V] =
    new Ctrie[K, V]

  private final val W = 5
  private final val wMask = 0x1f // 0b11111
  private final val maxLevel = 30

  private final val indent = "  "

  private final object NOTFOUND {
    final def as[A]: A =
      this.asInstanceOf[A]
    final def isNotFound[A](a: A): Boolean =
      equ(a, this.as[A])
  }

  private def idx(hash: Int, l: Int): Int = {
    val sh = W * l
    ((wMask << sh) & hash) >>> sh
  }

  private def flagpos(hash: Int, l: Int, bmp: Int): (Int, Int) = {
    val i = idx(hash, l)
    val flag = 1 << i
    val pos = Integer.bitCount((flag - 1) & bmp)
    (flag, pos)
  }

  private final class Gen {
    override def toString: String =
      s"Gen(${this.##.toHexString})"
  }

  private final class INode[K, V](val main: Ref[MainNode[K, V]], val gen: Ref[Gen])
    extends Branch[K, V] {

    def clean(lev: Int): Axn[Unit] = {
      this.main.unsafeDirectRead.flatMap {
        case m: CNode[K, V] =>
          m.toCompressed(lev).flatMap { nm =>
            this.main.unsafeCas(m, nm)
          }
        case _ =>
          Rxn.unit
      }
    }

    def cleanParent(i: INode[K, V], k: K, lev: Int)(implicit hs: Hash[K]): Axn[Unit] = {
      i.main.unsafeDirectRead.flatMap { m =>
        this.main.unsafeDirectRead.flatMap { pm =>
          pm match {
            case cn: CNode[_, _] =>
              val (flag, pos) = flagpos(hs.hash(k), lev, cn.bmp)
              if ((cn.bmp & flag) == 0) {
                Rxn.unit
              } else {
                val sub = cn(pos)
                if (sub ne i) {
                  Rxn.unit
                } else {
                  m match {
                    case m: TNode[_, _] =>
                      val ncn = cn.updated(pos, m.untombed)
                      this.main.unsafeCas(cn, ncn.toContracted(lev))
                    case _ =>
                      Rxn.unit
                  }
                }
              }
            case _ =>
              Rxn.unit
          }
        }
      }
    }

    def resurrect: Axn[Branch[K, V]] = this.main.unsafeDirectRead.map {
      case tn: TNode[_, _] => tn.untombed
      case _ => this
    }

    private[choam] override def debug: Rxn[Int, String] = Rxn.computed { level =>
      for {
        m <- main.unsafeDirectRead
        ms <- m.debug.provide(level)
      } yield (indent * level) + s"INode -> ${ms}"
    }
  }

  private[choam] sealed abstract class MainNode[K, V] {
    def isTomb: Boolean = false // override in TNode
    private[choam] def debug: Rxn[Int, String]
  }

  /** Ctrie node */
  private final class CNode[K, V](val bmp: Int, arr: Array[Branch[K, V]]) extends MainNode[K, V] {

    def apply(idx: Int): Branch[K, V] =
      arr(idx)

    def updated(idx: Int, value: Branch[K, V]): CNode[K, V] = {
      val newArr = new Array[Branch[K, V]](arr.length)
      Array.copy(arr, 0, newArr, 0, arr.length)
      newArr(idx) = value
      new CNode(bmp, newArr)
    }

    def inserted(pos: Int, flag: Int, value: Branch[K, V]): CNode[K, V] = {
      val newArr = new Array[Branch[K, V]](arr.length + 1)
      Array.copy(arr, 0, newArr, 0, pos)
      newArr(pos) = value
      Array.copy(arr, pos, newArr, pos + 1, arr.length - pos)
      new CNode(bmp | flag, newArr)
    }

    def removed(pos: Int, flag: Int): CNode[K, V] = {
      val newArr = new Array[Branch[K, V]](arr.length - 1)
      Array.copy(arr, 0, newArr, 0, pos)
      Array.copy(arr, pos + 1, newArr, pos, arr.length - pos - 1)
      new CNode(bmp ^ flag, newArr)
    }

    def mapped(f: Branch[K, V] => Axn[Branch[K, V]]): Axn[CNode[K, V]] = {
      Traverse[List].traverse(arr.toList)(f).map { lst =>
        new CNode[K, V](bmp = this.bmp, arr = lst.toArray)
      }
    }

    def toCompressed(lev: Int): Axn[MainNode[K, V]] = {
      this.mapped {
        case in: INode[_, _] => in.resurrect
        case sn: SNode[_, _] => Rxn.pure(sn)
      }.map { ncn =>
        ncn.toContracted(lev)
      }
    }

    def toContracted(lev: Int): MainNode[K, V] = {
      if ((lev > 0) && (this.arr.length == 1)) {
        this(0) match {
          case sn: SNode[_, _] =>
            sn.entomb
          case _ =>
            this
        }
      } else {
        this
      }
    }

    private[choam] override def debug: Rxn[Int, String] = Rxn.computed { level =>
      val els = Traverse[List].traverse(arr.toList)(_.debug.provide(level + 1))
      els.map { els =>
        s"CNode ${bmp.toHexString}\n" + els.mkString("\n")
      }
    }
  }

  private final object CNode {

    def doubleton[K, V](x: SNode[K, V], y: SNode[K, V], lev: Int, gen: Gen)(implicit hs: Hash[K]): MainNode[K, V] = {
      if (lev <= maxLevel) {
        val xi = idx(hs.hash(x.k), lev)
        val yi = idx(hs.hash(y.k), lev)
        val bmp = (1 << xi) | (1 << yi)
        if (xi == yi) {
          // hash collision at this level, go down:
          new CNode(bmp, Array[Branch[K, V]](new INode(Ref.unsafe(doubleton(x, y, lev + W, gen)), Ref.unsafe(gen))))
        } else {
          val arr = if (xi < yi) {
            Array[Branch[K, V]](x, y)
          } else {
            Array[Branch[K, V]](y, x)
          }
          new CNode(bmp, arr)
        }
      } else {
        new LNode(x.k, x.v, y.k, y.v)
      }
    }
  }

  /** Tomb node */
  private[choam] final class TNode[K, V](val k: K, val v: V) extends MainNode[K, V] {
    final override def isTomb: Boolean =
      true

    final def untombed: SNode[K, V] =
      new SNode[K, V](this.k, this.v)

    private[choam] def debug: Rxn[Int, String] = Rxn.lift { level =>
      (indent * level) + s"SNode(${k} -> ${v})"
    }
  }

  /** Immutable list */
  private sealed abstract class Lst[+A] {

    def :: [B >: A](item: B): Lst[B] =
      new Lst.Cons[B](item, this)

    def asCons[B >: A]: Lst.Cons[B]

    @tailrec
    final def foldLeft[B](z: B)(op: (B, A) => B): B = {
      this.asCons match {
        case null => z
        case c => c.t.foldLeft(op(z, c.h))(op)
      }
    }
  }

  private final object Lst {

    final class Cons[+A](
      final val h: A,
      final val t: Lst[A]
    ) extends Lst[A] {

      final override def asCons[B >: A]: Cons[B] =
        this
    }

    final object Nil
      extends Lst[Nothing] {

      final override def asCons[B >: Nothing]: Cons[B] =
        null
    }
  }

  /** List node */
  private[choam] final class LNode[K, V](
    private[choam] val key: K,
    private[choam] val value: V,
    private[choam] val next: LNode[K, V]
  ) extends MainNode[K, V] {

    def this(k1: K, v1: V, k2: K, v2: V) =
      this(k1, v1, new LNode(k2, v2, null))

    final def isSingleton: Boolean = {
      this.next eq null
    }

    final def asSNode: SNode[K, V] = {
      assert(this.isSingleton)
      new SNode[K, V](this.key, this.value)
    }

    def length: Int = {
      @tailrec
      def go(curr: LNode[K, V], acc: Int): Int = {
        if (curr eq null) acc
        else go(curr.next, acc = acc + 1)
      }
      go(this, 0)
    }

    def lookup(implicit eq: Eq[K]): Rxn[K, V] =
      Rxn.lift(get)

    @tailrec
    def get(k: K)(implicit eq: Eq[K]): V = {
      if (eq.eqv(k, key)) value
      else if (next eq null) NOTFOUND.as[V]
      else next.get(k)
    }

    def inserted(k: K, v: V)(implicit eq: Eq[K]): LNode[K, V] = {
      new LNode(k, v, this.removed(k))
    }

    def removed(k: K)(implicit eq: Eq[K]): LNode[K, V] = {

      @tailrec
      def go(curr: LNode[K, V], acc: Lst[LNode[K, V]]): LNode[K, V] = {
        if (eq.eqv(k, curr.key)) {
          // found the node to remove
          acc.foldLeft(curr.next) { (t, h) => new LNode(h.key, h.value, t) }
        } else if (curr.next eq null) {
          // end, nothing to remove
          this
        } else {
          // continue
          go(curr.next, curr :: acc)
        }
      }

      go(this, Lst.Nil)
    }

    private[choam] def debug: Rxn[Int, String] = Rxn.computed { _ =>
      val lst = this.dbg(Nil).reverse
      Rxn.ret(s"LNode(${lst.mkString(", ")})")
    }

    @tailrec
    private def dbg(acc: List[String]): List[String] = {
      val lst = s"${key} -> ${value}" :: acc
      if (next eq null) lst
      else next.dbg(lst)
    }
  }

  private[choam] sealed abstract class Branch[K, V] {
    private[choam] def debug: Rxn[Int, String]
  }

  /** Single node */
  private[choam] final class SNode[K, V](val k: K, val v: V) extends Branch[K, V] {

    def entomb: TNode[K, V] =
      new TNode[K, V](this.k, this.v)

    private[choam] override def debug: Rxn[Int, String] = Rxn.computed { level =>
      Rxn.ret((indent * level) + s"SNode(${k} -> ${v})")
    }
  }
}
