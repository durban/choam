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
package bench

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import data.Map
import util._

@Fork(2)
@Threads(2)
class DataMapBench {

  import DataMapBench._

  @Benchmark
  def baseline(s: BaselineSt, bh: Blackhole, k: KCASImplState): Unit = {
    baselineTask(s, bh, k)
  }

  @Benchmark
  def rxnSimple(s: SimpleSt, bh: Blackhole, k: KCASImplState): Unit = {
    rxnTask(s, bh, k)
  }

  @Benchmark
  def rxnTtrie(s: TtrieSt, bh: Blackhole, k: KCASImplState): Unit = {
    rxnTask(s, bh, k)
  }

  @Benchmark
  def scalaStm(s: ScalaStmSt, bh: Blackhole, k: KCASImplState): Unit = {
    scalaStmTask(s, bh, k)
  }

  private[this] final def rxnTask(s: RxnMapSt, bh: Blackhole, k: KCASImplState): Unit = {
    k.nextIntBounded(4) match {
      case 0 | 1 =>
        // lookup:
        val key = s.keys(k.nextIntBounded(s.keys.length))
        val res: String = s.map.get.unsafePerformInternal(key, k.kcasCtx).get
        bh.consume(res)
      case n @ (2 | 3) =>
        val key = s.delInsKeys(k.nextIntBounded(s.delInsKeys.length))
        if (n == 2) {
          // insert:
          val res: Option[String] = s.map.put.unsafePerformInternal((key, s.constValue), k.kcasCtx)
          bh.consume(res)
        } else {
          // remove:
          val res: Boolean = s.map.del.unsafePerformInternal(key, k.kcasCtx)
          bh.consume(res)
        }
      case x =>
        impossible(x.toString)
    }
  }

  private[this] final def scalaStmTask(s: ScalaStmSt, bh: Blackhole, k: KCASImplState): Unit = {
    import scala.concurrent.stm.atomic
    k.nextIntBounded(4) match {
      case 0 | 1 =>
        // lookup:
        val key = s.keys(k.nextIntBounded(s.keys.length))
        val res: String = atomic { implicit txn =>
          s.tmap.get(key)
        }.get
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

  private[this] final def baselineTask(s: AbstractSt, bh: Blackhole, k: KCASImplState): Unit = {
    k.nextIntBounded(4) match {
      case 0 | 1 =>
        // lookup:
        val key: String = s.keys(k.nextIntBounded(s.keys.length))
        bh.consume(key)
      case n @ (2 | 3) =>
        val key: String = s.delInsKeys(k.nextIntBounded(s.delInsKeys.length))
        if (n == 2) {
          // insert:
          val tup: (String, String) = (key, s.constValue)
          bh.consume(tup)
        } else {
          // remove:
          bh.consume(key)
        }
      case x =>
        impossible(x.toString)
    }
  }
}

object DataMapBench {

  final val size = 8

  private[this] final def initMcas: mcas.MCAS =
    mcas.MCAS.EMCAS

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

    def mapSize: Int =
      _mapSize

    def keys: Array[String] =
      _keys

    def delInsKeys: Array[String] =
      _delInsKeys

    @Setup
    final def setup(): Unit = {
      this.setupImpl()
    }

    protected def setupImpl(): Unit = {
      val delInsSize = mapSize >>> 4
      assert(delInsSize > 0)
      _delInsKeys = new Array[String](delInsSize)
      _keys = new Array[String](mapSize - delInsSize)
      assert((_keys.length + delInsSize) == mapSize)
      val rng = new scala.util.Random(ThreadLocalRandom.current())
      val set = scala.collection.mutable.Set.empty[String]
      while (set.size < mapSize) {
        val k = rng.nextString(32)
        set += k
      }
      val vec = rng.shuffle(set.toVector)
      assert(vec.length == mapSize)
      var idx = 0
      while (idx < _delInsKeys.length) {
        _delInsKeys(idx) = vec(idx)
        idx += 1
      }
      val offset = _delInsKeys.length
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

    private def initializeMap(): Unit = {
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
  class BaselineSt extends AbstractSt {
  }

  @State(Scope.Benchmark)
  class SimpleSt extends RxnMapSt {

    val simple: Map[String, String] =
      Map.simple[String, String].unsafeRun(initMcas)

    final def map: Map[String, String] =
      simple
  }

  @State(Scope.Benchmark)
  class TtrieSt extends RxnMapSt {

    val ttrie: Map[String, String] =
      Map.ttrie[String, String].unsafeRun(initMcas)

    final def map: Map[String, String] =
      ttrie
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
}
