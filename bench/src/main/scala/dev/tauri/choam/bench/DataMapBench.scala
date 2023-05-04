/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import data.Map
import util._

@Fork(2)
@Threads(2)
class DataMapBench {

  // TODO: measure hashing / comparing keys
  // TODO: measure Scala TrieMap (hash)

  import DataMapBench._

  @Benchmark
  def jucConcurrentHashMap(s: ChmSt, bh: Blackhole, k: McasImplState): Unit = {
    cmTask(s, bh, k)
  }

  @Benchmark
  def jucConcurrentSkipListMap(s: CslmSt, bh:Blackhole, k: McasImplState): Unit = {
    cmTask(s, bh, k)
  }

  @Benchmark
  def scalaStmTMap(s: ScalaStmSt, bh: Blackhole, k: McasImplState): Unit = {
    scalaStmTask(s, bh, k)
  }

  @Benchmark
  def rxnSimpleHash(s: SimpleHashSt, bh: Blackhole, k: McasImplState): Unit = {
    rxnTask(s, bh, k)
  }

  @Benchmark
  def rxnSimpleOrdered(s: SimpleOrderedSt, bh: Blackhole, k: McasImplState): Unit = {
    rxnTask(s, bh, k)
  }

  @Benchmark
  def rxnMapHash(s: TMapHashSt, bh: Blackhole, k: McasImplState): Unit = {
    rxnTask(s, bh, k)
  }

  @Benchmark
  def rxnMapOrdered(s: TMapOrderedSt, bh: Blackhole, k: McasImplState): Unit = {
    rxnTask(s, bh, k)
  }

  private[this] final def rxnTask(s: RxnMapSt, bh: Blackhole, k: McasImplState): Unit = {
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
        val res: Option[String] = s.map.get.unsafePerformInternal(key, k.mcasCtx)
        bh.consume(res)
      case n @ (2 | 3) =>
        val key = s.delInsKeys(k.nextIntBounded(s.delInsKeys.length))
        if (n == 2) {
          // insert:
          val res: Option[String] = s.map.put.unsafePerformInternal((key, s.constValue), k.mcasCtx)
          bh.consume(res)
        } else {
          // remove:
          val res: Boolean = s.map.del.unsafePerformInternal(key, k.mcasCtx)
          bh.consume(res)
        }
      case x =>
        impossible(x.toString)
    }
  }

  private[this] final def scalaStmTask(s: ScalaStmSt, bh: Blackhole, k: McasImplState): Unit = {
    import scala.concurrent.stm.atomic
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
          s.tmap.get(key)
        }
        bh.consume(res)
      case n @ (2 | 3) =>
        val key = s.delInsKeys(k.nextIntBounded(s.delInsKeys.length))
        if (n == 2) {
          // insert:
          val res: Option[String] = atomic { implicit txn =>
            s.tmap.put(key, s.constValue)
          }
          bh.consume(res)
        } else {
          // remove:
          val res: Boolean = atomic { implicit txn =>
            s.tmap.remove(key)
          }.isDefined
          bh.consume(res)
        }
      case x =>
        impossible(x.toString)
    }
  }

  private[this] final def cmTask(s: JucCmSt, bh: Blackhole, k: McasImplState): Unit = {
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
        val res: Option[String] = Option(s.cm.get(key))
        bh.consume(res)
      case n @ (2 | 3) =>
        val key = s.delInsKeys(k.nextIntBounded(s.delInsKeys.length))
        if (n == 2) {
          // insert:
          val res: Option[String] = Option(s.cm.put(key, s.constValue))
          bh.consume(res)
        } else {
          // remove:
          val res: String = s.cm.remove(key)
          bh.consume(res)
        }
      case x =>
        impossible(x.toString)
    }
  }
}

object DataMapBench {

  final val size = 8

  private[this] final def initMcas: mcas.Mcas =
    mcas.Mcas.Emcas

  @State(Scope.Benchmark)
  abstract class AbstractSt {

    final val constValue =
      "abcde"

    @Param(Array("1048576"))
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

    import java.util.concurrent.ConcurrentHashMap

    private[this] val chm: ConcurrentHashMap[String, String] =
      new ConcurrentHashMap[String, String]

    final override def cm =
      chm
  }

  @State(Scope.Benchmark)
  class CslmSt extends JucCmSt {

    import java.util.concurrent.ConcurrentSkipListMap

    val cslm: ConcurrentSkipListMap[String, String] =
      new ConcurrentSkipListMap[String, String]

    final override def cm =
      cslm
  }
}
