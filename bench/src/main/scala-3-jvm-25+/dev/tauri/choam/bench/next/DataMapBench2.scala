/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package next

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import kyo.{ <, Kyo, STM, TMap, Sync, Async, Fiber, Abort, Maybe, Loop }

import util._
import datastruct.DataMapBench

@Fork(2)
@Threads(2)
class DataMapBench2 {

  import DataMapBench2.{ KyoTMapSt, CeRtSt }

  private[this] final val N = 64

  @Benchmark
  def hash_rxn(s: DataMapBench.TMapHashSt, bh: Blackhole, rnd: RandomState, rt: CeRtSt): Unit = {
    this.rxnTask(s, bh, rnd).replicateA_(N).unsafeRunSync()(using rt.runtime)
  }

  @Benchmark
  def hash_kyo(s: KyoTMapSt, bh: Blackhole, rnd: RandomState): Unit = {
    s.forkAndBlockKyo {
      Kyo.foreachDiscard(1 to N) { _ =>
        this.kyoTask(s, bh, rnd)
      }
    }
  }

  private[this] final def rxnTask(s: DataMapBench.RxnMapSt, bh: Blackhole, rnd: RandomState): IO[Unit] = {
    val map = s.map
    IO(rnd.nextIntBounded(4)).flatMap {
      case n @ (0 | 1) =>
        for {
          key <- if (n == 0) {
            // successful lookup:
            val keys = s.keys
            IO(keys(rnd.nextIntBounded(keys.length)))
          } else {
            // unsuccessful lookup:
            val dummyKeys = s.dummyKeys
            IO(dummyKeys(rnd.nextIntBounded(dummyKeys.length)))
          }
          res <- map.get(key).run[IO](using s.reactive)
          _ <- IO(bh.consume(res : Option[String]))
        } yield ()
      case n @ (2 | 3) =>
        val delInsKeys = s.delInsKeys
        IO(delInsKeys(rnd.nextIntBounded(delInsKeys.length))).flatMap { key =>
          if (n == 2) {
            // insert:
            map.put(key, s.constValue).run[IO](using s.reactive).flatMap { (res: Option[String]) =>
              IO(bh.consume(res))
            }
          } else {
            // remove:
            map.del(key).run[IO](using s.reactive).flatMap { (res: Boolean) =>
              IO(bh.consume(res))
            }
          }
        }
      case x =>
        impossible(x.toString)
    }
  }

  private[this] final def kyoTask(s: KyoTMapSt, bh: Blackhole, rnd: RandomState): Unit < (Async & Abort[Throwable]) = {
    val map = s.map
    Sync.defer(rnd.nextIntBounded(4)).map {
      case n @ (0 | 1) =>
        for {
          key <- if (n == 0) {
            // successful lookup:
            val keys = s.keys
            Sync.defer(keys(rnd.nextIntBounded(keys.length)))
          } else {
            // unsuccessful lookup:
            val dummyKeys = s.dummyKeys
            Sync.defer(dummyKeys(rnd.nextIntBounded(dummyKeys.length)))
          }
          res <- STM.run(map.get(key))
          _ <- Sync.defer(bh.consume(res : Maybe[String]))
        } yield ()
      case n @ (2 | 3) =>
        val delInsKeys = s.delInsKeys
        Sync.defer(delInsKeys(rnd.nextIntBounded(delInsKeys.length))).map { key =>
          if (n == 2) {
            // insert:
            STM.run(map.put(key, s.constValue))
          } else {
            // remove:
            STM.run(map.remove(key)).map { (res: Maybe[String]) =>
              Sync.defer(bh.consume(res))
            }
          }
        }
      case x =>
        impossible(x.toString)
    }
  }
}

object DataMapBench2 {

  @State(Scope.Benchmark)
  class CeRtSt {
    val runtime: IORuntime =
      IORuntime.global
  }

  @State(Scope.Benchmark)
  class KyoTMapSt extends DataMapBench.AbstractSt {

    val map: TMap[String, String] = {
      import kyo.AllowUnsafe.embrace.danger
      Abort.run(kyo.Sync.Unsafe.run(TMap.init)).eval.getOrThrow
    }

    private[choam] final def forkAndBlockKyo[A](tsk: A < (Async & Abort[Throwable])): A = {
      import kyo.AllowUnsafe.embrace.danger
      Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(tsk).map(_.block(kyo.Duration.Infinity))).getOrThrow
    }

    override def setupImpl(mapSize: Int): Unit = {
      super.setupImpl(mapSize)
      forkAndBlockKyo(this.initializeMap(mapSize))
    }

    private[this] def initializeMap(@unused mapSize: Int): Unit < (Async & Abort[Throwable]) = {
      val txn1 = Loop(0) { idx =>
        if (idx < delInsKeys.length) {
          val key = delInsKeys(idx)
          assert(key ne null)
          this.map.put(key, constValue) `andThen` Loop.continue(idx + 1)
        } else {
          Loop.done
        }
      }
      val txn2 = Loop(0) { idx =>
        if (idx < keys.length) {
          val key = keys(idx)
          assert(key ne null)
          this.map.put(key, constValue) `andThen` Loop.continue(idx + 1)
        } else {
          Loop.done
        }
      }
      STM.run(txn1 `andThen` txn2)
    }
  }
}
