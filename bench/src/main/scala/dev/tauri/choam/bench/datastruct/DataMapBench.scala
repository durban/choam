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
package bench
package datastruct

import java.util.concurrent.ThreadLocalRandom

import cats.kernel.{ Order, Hash }

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import internal.mcas.Mcas
import data.Map
import util._

@Fork(2)
@Threads(2)
class DataMapBench {

  import DataMapBench._

  @Benchmark
  def hash_jucCM(s: ChmSt, bh: Blackhole, k: McasImplState): Unit = {
    cmTask(s, bh, k)
  }

  @Benchmark
  def ordered_jucCSLM(s: CslmSt, bh:Blackhole, k: McasImplState): Unit = {
    cmTask(s, bh, k)
  }

  @Benchmark
  def hash_sccTrieMap(s: TmSt, bh:Blackhole, k: McasImplState): Unit = {
    tmTask(s, bh, k)
  }

  @Benchmark
  def hash_scalaStm(s: ScalaStmSt, bh: Blackhole, k: McasImplState): Unit = {
    scalaStmTask(s, bh, k)
  }

  @Benchmark
  def hash_rxnSimple(s: SimpleHashSt, bh: Blackhole, k: McasImplState): Unit = {
    rxnTask(s, bh, k)
  }

  @Benchmark
  def ordered_rxnSimple(s: SimpleOrderedSt, bh: Blackhole, k: McasImplState): Unit = {
    rxnTask(s, bh, k)
  }

  @Benchmark
  def hash_rxn(s: TMapHashSt, bh: Blackhole, k: McasImplState): Unit = {
    rxnTask(s, bh, k)
  }

  @Benchmark
  def ordered_rxn(s: TMapOrderedSt, bh: Blackhole, k: McasImplState): Unit = {
    rxnTask(s, bh, k)
  }

  private[this] final def rxnTask(s: RxnMapSt, bh: Blackhole, k: McasImplState): Unit = {
    val mcasCtx = k.mcasCtx
    val map = s.map
    k.nextIntBounded(4) match {
      case n @ (0 | 1) =>
        val key = if (n == 0) {
          // successful lookup:
          val keys = s.keys
          keys(k.nextIntBounded(keys.length))
        } else {
          // unsuccessful lookup:
          val dummyKeys = s.dummyKeys
          dummyKeys(k.nextIntBounded(dummyKeys.length))
        }
        val res: Option[String] = map.get.unsafePerformInternal(key, mcasCtx)
        bh.consume(res)
      case n @ (2 | 3) =>
        val delInsKeys = s.delInsKeys
        val key = delInsKeys(k.nextIntBounded(delInsKeys.length))
        if (n == 2) {
          // insert:
          val res: Option[String] = map.put.unsafePerformInternal((key, s.constValue), mcasCtx)
          bh.consume(res)
        } else {
          // remove:
          val res: Boolean = map.del.unsafePerformInternal(key, mcasCtx)
          bh.consume(res)
        }
      case x =>
        impossible(x.toString)
    }
  }

  private[this] final def scalaStmTask(s: ScalaStmSt, bh: Blackhole, k: McasImplState): Unit = {
    import scala.concurrent.stm.atomic
    val tmap = s.tmap
    k.nextIntBounded(4) match {
      case n @ (0 | 1) =>
        val key = if (n == 0) {
          // successful lookup:
          val keys = s.keys
          keys(k.nextIntBounded(keys.length))
        } else {
          // unsuccessful lookup:
          val dummyKeys = s.dummyKeys
          dummyKeys(k.nextIntBounded(dummyKeys.length))
        }
        val res: Option[String] = atomic { implicit txn =>
          tmap.get(key)
        }
        bh.consume(res)
      case n @ (2 | 3) =>
        val delInsKeys = s.delInsKeys
        val key = delInsKeys(k.nextIntBounded(delInsKeys.length))
        if (n == 2) {
          // insert:
          val res: Option[String] = atomic { implicit txn =>
            tmap.put(key, s.constValue)
          }
          bh.consume(res)
        } else {
          // remove:
          val res: Boolean = atomic { implicit txn =>
            tmap.remove(key)
          }.isDefined
          bh.consume(res)
        }
      case x =>
        impossible(x.toString)
    }
  }

  private[this] final def cmTask(s: JucCmSt, bh: Blackhole, k: McasImplState): Unit = {
    val cm = s.cm
    k.nextIntBounded(4) match {
      case n @ (0 | 1) =>
        val key = if (n == 0) {
          // successful lookup:
          val keys = s.keys
          keys(k.nextIntBounded(keys.length))
        } else {
          // unsuccessful lookup:
          val dummyKeys = s.dummyKeys
          dummyKeys(k.nextIntBounded(dummyKeys.length))
        }
        val res: Option[String] = Option(cm.get(key))
        bh.consume(res)
      case n @ (2 | 3) =>
        val delInsKeys = s.delInsKeys
        val key = delInsKeys(k.nextIntBounded(delInsKeys.length))
        if (n == 2) {
          // insert:
          val res: Option[String] = Option(cm.put(key, s.constValue))
          bh.consume(res)
        } else {
          // remove:
          val res: String = cm.remove(key)
          bh.consume(res)
        }
      case x =>
        impossible(x.toString)
    }
  }

  private[this] final def tmTask(s: TmSt, bh: Blackhole, k: McasImplState): Unit = {
    val tm = s.tm
    k.nextIntBounded(4) match {
      case n @ (0 | 1) =>
        val key = if (n == 0) {
          // successful lookup:
          val keys = s.keys
          keys(k.nextIntBounded(keys.length))
        } else {
          // unsuccessful lookup:
          val dummyKeys = s.dummyKeys
          dummyKeys(k.nextIntBounded(dummyKeys.length))
        }
        val res: Option[String] = tm.get(key)
        bh.consume(res)
      case n @ (2 | 3) =>
        val delInsKeys = s.delInsKeys
        val key = delInsKeys(k.nextIntBounded(delInsKeys.length))
        if (n == 2) {
          // insert:
          val res: Option[String] = tm.put(key, s.constValue)
          bh.consume(res)
        } else {
          // remove:
          val res: Option[String] = tm.remove(key)
          bh.consume(res)
        }
      case x =>
        impossible(x.toString)
    }
  }
}

object DataMapBench {

  final val size = 8

  private[this] final def initMcas: Mcas =
    Mcas.Emcas

  @State(Scope.Benchmark)
  abstract class AbstractSt {

    final val constValue =
      "abcde"

    @Param(Array("1048576"))
    @nowarn("cat=unused-privates")
    private[this] var _mapSize: Int =
      -1

    private[this] var _keys: Array[String] =
      null

    private[this] var _delInsKeys: Array[String] =
      null

    private[this] var _dummyKeys: Array[String] =
      null

    final def mapSize: Int =
      _mapSize

    /** Keys, which are initially included in the map, and never removed */
    final def keys: Array[String] =
      _keys

    /** Keys, which are initially included in the map, and repeately removed/inserted */
    final def delInsKeys: Array[String] =
      _delInsKeys

    /** Keys, which are not included in the map, and used for unsuccessful lookups */
    final def dummyKeys: Array[String] =
      _dummyKeys

    @Setup
    final def setup(): Unit = {
      this.setupImpl()
    }

    protected def setupImpl(): Unit = {
      val delInsSize = mapSize >>> 4
      val dummySize = delInsSize
      assert(delInsSize > 0)
      _delInsKeys = new Array[String](delInsSize)
      _dummyKeys = new Array[String](dummySize)
      _keys = new Array[String](mapSize - delInsSize)
      assert((_keys.length + delInsSize) == mapSize)
      val fullSize = mapSize + dummySize
      val rng = new scala.util.Random(ThreadLocalRandom.current())
      val set = scala.collection.mutable.Set.empty[String]
      while (set.size < fullSize) {
        val k = rng.nextString(32)
        set += k
      }
      val vec = rng.shuffle(set.toVector)
      assert(vec.length == fullSize)
      var idx = 0
      while (idx < _delInsKeys.length) {
        _delInsKeys(idx) = vec(idx)
        idx += 1
      }
      var offset = _delInsKeys.length
      idx = 0
      while (idx < _dummyKeys.length) {
        _dummyKeys(idx) = vec(idx + offset)
        idx += 1
      }
      offset += _dummyKeys.length
      idx = 0
      while (idx < _keys.length) {
        _keys(idx) = vec(idx + offset)
        idx += 1
      }
    }
  }

  @State(Scope.Benchmark)
  class DummySt extends AbstractSt {

    val order: Order[String] =
      Order[String]

    val hash: Hash[String] =
      Hash[String]
  }

  @State(Scope.Benchmark)
  abstract class RxnMapSt extends AbstractSt {

    def map: Map[String, String]

    protected override def setupImpl(): Unit = {
      super.setupImpl()
      this.initializeMap()
    }

    private[this] def initializeMap(): Unit = {
      var idx = 0
      while (idx < delInsKeys.length) {
        val key = delInsKeys(idx)
        assert(key ne null)
        assert(this.map.put.unsafePerform(key -> constValue, initMcas).isEmpty)
        idx += 1
      }
      idx = 0
      while (idx < keys.length) {
        val key = keys(idx)
        assert(key ne null)
        assert(this.map.put.unsafePerform(key -> constValue, initMcas).isEmpty)
        idx += 1
      }
    }
  }

  @State(Scope.Benchmark)
  class SimpleHashSt extends RxnMapSt {

    private[this] val simple: Map[String, String] =
      Map.simpleHashMap[String, String].unsafeRun(initMcas)

    final def map: Map[String, String] =
      simple
  }

  @State(Scope.Benchmark)
  class SimpleOrderedSt extends RxnMapSt {

    private[this] val simple: Map[String, String] =
      Map.simpleOrderedMap[String, String].unsafeRun(initMcas)

    final def map: Map[String, String] =
      simple
  }

  @State(Scope.Benchmark)
  class TMapHashSt extends RxnMapSt {

    private[this] val m: Map[String, String] =
      Map.hashMap[String, String].unsafeRun(initMcas)

    final def map: Map[String, String] =
      m
  }

  @State(Scope.Benchmark)
  class TMapOrderedSt extends RxnMapSt {

    private[this] val m: Map[String, String] =
      Map.orderedMap[String, String].unsafeRun(initMcas)

    final def map: Map[String, String] =
      m
  }

  @State(Scope.Benchmark)
  class ScalaStmSt extends AbstractSt {

    import scala.concurrent.stm.atomic
    import scala.concurrent.stm.TMap

    val tmap: TMap[String, String] =
      TMap.empty[String, String]

    protected override def setupImpl(): Unit = {
      super.setupImpl()
      this.initializeMap()
    }

    private def initializeMap(): Unit = {
      var idx = 0
      while (idx < delInsKeys.length) {
        val key = delInsKeys(idx)
        assert(key ne null)
        assert(atomic { implicit txn => this.tmap.put(key, constValue) }.isEmpty)
        idx += 1
      }
      idx = 0
      while (idx < keys.length) {
        val key = keys(idx)
        assert(key ne null)
        assert(atomic { implicit txn => this.tmap.put(key, constValue) }.isEmpty)
        idx += 1
      }
      assert(atomic { implicit txn => this.tmap.size } == this.mapSize)
    }
  }

  @State(Scope.Benchmark)
  abstract class JucCmSt extends AbstractSt {

    def cm: java.util.concurrent.ConcurrentMap[String, String]

    protected final override def setupImpl(): Unit = {
      super.setupImpl()
      this.initializeMap()
    }

    private[this] final def initializeMap(): Unit = {
      var idx = 0
      while (idx < delInsKeys.length) {
        val key = delInsKeys(idx)
        assert(key ne null)
        assert(this.cm.put(key, constValue) eq null)
        idx += 1
      }
      idx = 0
      while (idx < keys.length) {
        val key = keys(idx)
        assert(key ne null)
        assert(this.cm.put(key, constValue) eq null)
        idx += 1
      }
      assert(this.cm.size() == this.mapSize)
    }
  }

  @State(Scope.Benchmark)
  class ChmSt extends JucCmSt {

    import java.util.concurrent.{ ConcurrentMap, ConcurrentHashMap }

    private[this] val chm: ConcurrentHashMap[String, String] =
      new ConcurrentHashMap[String, String]

    final override def cm: ConcurrentMap[String, String] =
      chm
  }

  @State(Scope.Benchmark)
  class CslmSt extends JucCmSt {

    import java.util.concurrent.{ ConcurrentMap, ConcurrentSkipListMap }

    val cslm: ConcurrentSkipListMap[String, String] =
      new ConcurrentSkipListMap[String, String]

    final override def cm:ConcurrentMap[String, String] =
      cslm
  }

  @State(Scope.Benchmark)
  class TmSt extends AbstractSt {

    import scala.collection.concurrent.TrieMap

    val tm: TrieMap[String, String] =
      new TrieMap

    protected final override def setupImpl(): Unit = {
      super.setupImpl()
      this.initializeMap()
    }

    private[this] final def initializeMap(): Unit = {
      var idx = 0
      while (idx < delInsKeys.length) {
        val key = delInsKeys(idx)
        assert(key ne null)
        assert(this.tm.put(key, constValue).isEmpty)
        idx += 1
      }
      idx = 0
      while (idx < keys.length) {
        val key = keys(idx)
        assert(key ne null)
        assert(this.tm.put(key, constValue).isEmpty)
        idx += 1
      }
      assert(this.tm.size == this.mapSize)
    }
  }
}
