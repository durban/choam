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

import cats.{ effect => ce }
import cats.effect.IO
import cats.effect.unsafe.IORuntime

import core.Reactive

import kyo.{ <, Kyo, STM, TMap, Sync, Async, Fiber, Abort, Maybe, Loop }

import util._
import datastruct.DataMapBench

@Fork(2)
@Threads(2)
class DataMapBench2 {

  import DataMapBench2.{ KyoTMapSt, CeRtSt, KyoRtSt, forkAndBlockKyo }

  private[this] final val N = 64

  @Benchmark
  def hash_kyo(s: KyoTMapSt, bh: Blackhole, rnd: RandomState): Unit = {
    forkAndBlockKyo {
      Kyo.foreachDiscard(1 to N) { _ =>
        this.kyoTask(s, bh, rnd)
      }
    }
  }

  @Benchmark
  def hash_rxnOnCe(s: DataMapBench.TMapHashSt, bh: Blackhole, rnd: RandomState, rt: CeRtSt): Unit = {
    this.rxnTask[IO](s, bh, rnd)(using IO.asyncForIO, s.reactive).replicateA_(N).unsafeRunSync()(using rt.runtime)
  }

  @Benchmark
  def hash_rxnOnKyo(s: DataMapBench.TMapHashSt, bh: Blackhole, rnd: RandomState, rt: KyoRtSt): Unit = {
    // TODO: in this case, there is still a separate ChoamRuntime and Reactive instance (in `s`)
    forkAndBlockKyo {
      Kyo.foreachDiscard(1 to N) { _ =>
        this.rxnTask[<[*, Async & Abort[Throwable]]](s, bh, rnd)(
          using rt.converter.syncInstance, rt.converter.reactiveInstance
        )
      }
    }
  }

  private[this] final def rxnTask[F[_]](s: DataMapBench.RxnMapSt, bh: Blackhole, rnd: RandomState)(
    using
    F: ce.Sync[F],
    rF: Reactive[F],
  ): F[Unit] = {
    import cats.syntax.all._

    val map = s.map
    F.flatMap(F.delay(rnd.nextIntBounded(4))) {
      case n @ (0 | 1) =>
        for {
          key <- if (n == 0) {
            // successful lookup:
            val keys = s.keys
            F.delay(keys(rnd.nextIntBounded(keys.length)))
          } else {
            // unsuccessful lookup:
            val dummyKeys = s.dummyKeys
            F.delay(dummyKeys(rnd.nextIntBounded(dummyKeys.length)))
          }
          res <- map.get(key).run[F]
          _ <- F.delay(bh.consume(res : Option[String]))
        } yield ()
      case n @ (2 | 3) =>
        val delInsKeys = s.delInsKeys
        F.delay(delInsKeys(rnd.nextIntBounded(delInsKeys.length))).flatMap { key =>
          if (n == 2) {
            // insert:
            map.put(key, s.constValue).run[F].flatMap { (res: Option[String]) =>
              F.delay(bh.consume(res))
            }
          } else {
            // remove:
            map.del(key).run[F].flatMap { (res: Boolean) =>
              F.delay(bh.consume(res))
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

  private[choam] final def forkAndBlockKyo[A](tsk: A < (Async & Abort[Throwable])): A = {
    import kyo.AllowUnsafe.embrace.danger
    Sync.Unsafe.evalOrThrow(Fiber.initUnscoped(tsk).map(_.block(kyo.Duration.Infinity))).getOrThrow
  }

  @State(Scope.Benchmark)
  class CeRtSt {
    val runtime: IORuntime =
      IORuntime.global
  }

  @State(Scope.Benchmark)
  class KyoRtSt {
    val converter: Converter =
      new Converter(ChoamRuntime.unsafeBlocking()) // TODO: don't create new RT
  }

  @State(Scope.Benchmark)
  class KyoTMapSt extends DataMapBench.AbstractSt {

    val map: TMap[String, String] = {
      import kyo.AllowUnsafe.embrace.danger
      Abort.run(kyo.Sync.Unsafe.run(TMap.init)).eval.getOrThrow
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
