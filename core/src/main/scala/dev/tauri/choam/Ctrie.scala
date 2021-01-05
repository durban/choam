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

import cats.{ Eq, Traverse }
import cats.instances.list._

import kcas.{ Ref, KCAS }

/**
 * Concurrent hash trie, described in [Concurrent Tries with Efficient
 * Non-Blocking Snapshots](http://lampwww.epfl.ch/~prokopec/ctries-snapshot.pdf)
 * by Aleksandar Prokopec, Nathan G. Bronson, Phil Bagwell and Martin Odersky.
 */
final class Ctrie[K, V](hs: K => Int, eq: Eq[K]) {

  // TODO: Cache-trie:
  // TODO: http://aleksandar-prokopec.com/resources/docs/p137-prokopec.pdf
  // TODO: http://aleksandar-prokopec.com/resources/docs/cachetrie-remove.pdf

  // TODO: use Hash
  // TODO: optimize (only compute hash code once, store hash code in trie)

  import Ctrie._

  private[this] val root: Ref[INode[K, V]] =
    Ref.mk(new INode(Ref.mk[MainNode[K, V]](new CNode(0, Array.empty)), Ref.mk(new Gen)))

  val lookup: React[K, Option[V]] = {

    def ilookup(i: INode[K, V], k: K, lev: Int, @deprecated("", "") parent: INode[K, V]): React[Unit, V] = {
      for {
        im <- i.main.invisibleRead
        v <- im match {
          case cn: CNode[K, V] =>
            val (flag, pos) = flagpos(hs(k), lev, cn.bmp)
            if ((cn.bmp & flag) == 0) {
              React.ret(NOTFOUND.as[V])
            } else {
              cn(pos) match {
                case sin: INode[K, V] =>
                  ilookup(sin, k, lev + W, i)
                case sn: SNode[K, V] =>
                  val r = if (eq.eqv(sn.k, k)) sn.v else NOTFOUND.as[V]
                  React.ret(r)
              }
            }
          case _: TNode[K, V] =>
            // TODO: clean
            React.retry
          case ln: LNode[K, V] =>
            ln.lookup.lmap[Unit](_ => (k, eq))
        }
        _ <- i.main.cas(im, im) // FIXME: this is only to be composable
      } yield v
    }

    React.computed[K, Option[V]] { k =>
      for {
        r <- root.invisibleRead
        v <- ilookup(r, k, 0, null)
      } yield if (equ(v, NOTFOUND.as[V])) None else Some(v)
    }
  }

  val insert: React[(K, V), Unit] = {

    def iinsert(i: INode[K, V], k: K, v: V, lev: Int, @deprecated("", "") parent: INode[K, V]): React[Unit, Unit] = {
      for {
        im <- i.main.invisibleRead
        gen <- i.gen.invisibleRead
        _ <- im match {
          case cn: CNode[K, V] =>
            val (flag, pos) = flagpos(hs(k), lev, cn.bmp)
            if ((cn.bmp & flag) == 0) {
              val ncn = cn.inserted(pos, flag, new SNode(k, v))
              i.main.cas(cn, ncn)
            } else {
              cn(pos) match {
                case sin: INode[K, V] =>
                  iinsert(sin, k, v, lev + W, i)
                case sn: SNode[K, V] =>
                  val ncn = if (!eq.eqv(sn.k, k)) {
                    val nsn = new SNode(k, v)
                    val nin = new INode(
                      Ref.mk[MainNode[K, V]](CNode.doubleton(sn, nsn, hs, lev + W, gen)), Ref.mk(gen)
                    )
                    cn.updated(pos, nin)
                  } else {
                    cn.updated(pos, new SNode(k, v))
                  }
                  i.main.cas(cn, ncn)
              }
            }
          case _: TNode[K, V] =>
            // TODO: clean
            React.retry
          case ln: LNode[K, V] =>
            i.main.cas(ln, ln.inserted(k, v, eq))
        }
      } yield ()
    }

    React.computed[(K, V), Unit] {
      case (k, v) =>
        for {
          r <- root.invisibleRead
          _ <- iinsert(r, k, v, 0, null)
        } yield ()
    }
  }

  @inline
  private def flagpos(hash: Int, l: Int, bmp: Int): (Int, Int) = {
    val i = idx(hash, l)
    val flag = 1 << i
    val pos = Integer.bitCount((flag - 1) & bmp)
    (flag, pos)
  }

  /** Only call in quiescent states! */
  private[choam] def debugStr(kcas: KCAS): String = {
    debug.unsafePerform(0, kcas)
  }

  private[this] def debug: React[Int, String] = React.computed { level =>
    for {
      r <- root.invisibleRead
      rs <- r.debug.lmap[Unit](_ => level)
    } yield rs
  }
}

object Ctrie {

  private final val W = 5
  private final val wMask = 0x1f // 0b11111
  private final val maxLevel = 30

  private final val indent = "  "

  private object NOTFOUND {
    def as[A]: A = this.asInstanceOf[A]
  }

  private def idx(hash: Int, l: Int): Int = {
    val sh = W * l
    ((wMask << sh) & hash) >>> sh
  }

  final class Gen {
    override def toString: String =
      s"Gen(${this.##.toHexString})"
  }

  final class INode[K, V](val main: Ref[MainNode[K, V]], val gen: Ref[Gen])
    extends Branch[K, V] {

    private[choam] override def debug: React[Int, String] = React.computed { level =>
      for {
        m <- main.invisibleRead
        ms <- m.debug.lmap[Unit](_ => level)
      } yield (indent * level) + s"INode -> ${ms}"
    }
  }

  sealed abstract class MainNode[K, V] {
    private[choam] def debug: React[Int, String]
  }

  /** Ctrie node */
  final class CNode[K, V](val bmp: Int, arr: Array[Branch[K, V]]) extends MainNode[K, V] {

    def apply(idx: Int): Branch[K, V] =
      arr(idx)

    def updated(idx: Int, value: Branch[K, V]): CNode[K, V] = {
      val newArr = Array.ofDim[Branch[K, V]](arr.length)
      Array.copy(arr, 0, newArr, 0, arr.length)
      newArr(idx) = value
      new CNode(bmp, newArr)
    }

    def inserted(pos: Int, flag: Int, value: Branch[K, V]): CNode[K, V] = {
      val newArr = Array.ofDim[Branch[K, V]](arr.length + 1)
      Array.copy(arr, 0, newArr, 0, pos)
      newArr(pos) = value
      Array.copy(arr, pos, newArr, pos + 1, arr.length - pos)
      new CNode(bmp | flag, newArr)
    }

    private[choam] override def debug: React[Int, String] = React.computed { level =>
      val els = Traverse[List].traverse(arr.toList)(_.debug.lmap[Unit](_ => level + 1))
      els.map { els =>
        s"CNode ${bmp.toHexString}\n" + els.mkString("\n")
      }
    }
  }

  object CNode {

    def doubleton[K, V](x: SNode[K, V], y: SNode[K, V], hs: K => Int, lev: Int, gen: Gen): MainNode[K, V] = {
      if (lev <= maxLevel) {
        val xi = idx(hs(x.k), lev)
        val yi = idx(hs(y.k), lev)
        val bmp = (1 << xi) | (1 << yi)
        if (xi == yi) {
          // hash collision at this level, go down:
          new CNode(bmp, Array[Branch[K, V]](new INode(Ref.mk(doubleton(x, y, hs, lev + W, gen)), Ref.mk(gen))))
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
  final class TNode[K, V](val sn: Ref[SNode[K, V]]) extends MainNode[K, V] {
    private[choam] def debug: React[Int, String] = React.computed { level =>
      sn.invisibleRead.flatMap(_.debug.lmap[Unit](_ => 0)).map(s => (indent * level) + s"TNode(${s})")
    }
  }

  /** List node */
  final class LNode[K, V](key: K, value: V, next: LNode[K, V]) extends MainNode[K, V] {

    def this(k1: K, v1: V, k2: K, v2: V) =
      this(k1, v1, new LNode(k2, v2, null))

    val lookup: React[(K, Eq[K]), V] =
      React.lift((get _).tupled)

    @tailrec
    def get(k: K, eq: Eq[K]): V = {
      if (eq.eqv(k, key)) value
      else if (next eq null) NOTFOUND.as[V]
      else next.get(k, eq)
    }

    def inserted(k: K, v: V, eq: Eq[K]): LNode[K, V] = {
      new LNode(k, v, this.removed(k, eq))
    }

    // TODO: @tailrec
    def removed(k: K, eq: Eq[K]): LNode[K, V] = {
      if (eq.eqv(k, key)) {
        next
      } else if (next eq null) {
        this
      } else {
        val nn = next.removed(k, eq)
        if (nn eq next) this
        else new LNode(key, value, nn)
      }
    }

    private[choam] def debug: React[Int, String] = React.computed { _ =>
      val lst = this.dbg(Nil).reverse
      React.ret(s"LNode(${lst.mkString(", ")})")
    }

    @tailrec
    private def dbg(acc: List[String]): List[String] = {
      val lst = s"${key} -> ${value}" :: acc
      if (next eq null) lst
      else next.dbg(lst)
    }
  }

  sealed abstract class Branch[K, V] {
    private[choam] def debug: React[Int, String]
  }

  /** Single node */
  final class SNode[K, V](val k: K, val v: V) extends Branch[K, V] {
    private[choam] override def debug: React[Int, String] = React.computed { level =>
      React.ret((indent * level) + s"SNode(${k} -> ${v})")
    }
  }
}
