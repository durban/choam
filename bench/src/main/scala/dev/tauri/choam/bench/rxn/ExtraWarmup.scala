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
package rxn

import java.util.concurrent.atomic.{ AtomicBoolean }

import scala.runtime.BooleanRef

import cats.effect.IO

import org.openjdk.jmh.annotations.{ State, Scope, Param, Setup }

import core.{ Rxn, Ref }
import util._

/**
 * To avoid the JIT overfitting on specific benchmark operations,
 * this JMH `@State` is running various random operations in its
 * `@Setup` method. We expect this to decrease performance, but
 * make it closer to real-world performance.
 *
 * The idea is from https://github.com/getkyo/kyo/pull/621.
 *
 * TODO: Currently this class only supports operations in an
 * TODO: effect `F[_]`. We also need a variant without that.
 */
@State(Scope.Benchmark)
abstract class ExtraWarmup[F[_]] extends GenMcasImplStateBase[F] {

  @Param(Array("10"))
  var warmupSeconds: Int =
    -1

  @Param(Array("0", "1", "2", "3"))
  var warmupMode: Int =
    -1

  @volatile
  private[ExtraWarmup] var warmupResult: Int =
    0

  private[ExtraWarmup] val ref1: Ref[String] =
    runAndBlock(Ref("foo").run[F])

  private[ExtraWarmup] val ref2: Ref[String] =
    runAndBlock(Ref("bar").run[F])

  @Setup
  def setup(): Unit = {
    runWarmup(this.warmupSeconds, this.warmupMode)
  }

  final def runWarmup(seconds: Int, mode: Int): Unit = {
    require(seconds >= 0)
    require((mode >= 0) && (mode <= 3))
    if ((seconds > 0) && (mode > 0)) {
      val stopFlag = new AtomicBoolean(false)
      val numCpu = Runtime.getRuntime().availableProcessors()
      val f: Runnable = () => { doEverything(stopFlag, mode) }
      val threads = Vector.fill(numCpu) { new Thread(f) }
      System.err.println(s"Running warmup for ${seconds}s")
      threads.foreach(_.start())
      try {
        Thread.sleep(seconds.toLong * 1000L)
      } finally {
        stopFlag.set(true)
        threads.foreach(_.join())
      }
      System.err.println("Warmup done")
    }
  }

  private[this] final def doEverything(stopFlag: AtomicBoolean, mode: Int): Unit = {
    val retry = BooleanRef.create(true)
    val tsk: F[Unit] = this.task(retry, mode)
    this.runAndBlock(
      this.syncF.whileM_(this.syncF.delay { !stopFlag.get() }) {
        tsk
      }
    )
  }

  private[this] final def task(flag: BooleanRef, mode: Int): F[Unit] = {
    val tsk: F[String] = mode match {
      case 0 => // neither
        syncF.pure("x")
      case 1 => // Rxn only
        doEverythingRxn(flag).run[F]
      case 2 => // F only
        doEverythingF(flag)
      case 3 => // both
        syncF.flatMap(doEverythingRxn(flag).run[F]) { (s1: String) =>
          syncF.map(doEverythingF(flag)) { (s2: String) =>
            s"${s1}, ${s2}"
          }
        }
      case _ =>
        throw new AssertionError(mode.toString)
    }
    syncF.flatMap(tsk) { s =>
      syncF.delay { this.warmupResult = s.hashCode }
    }
  }

  private[this] final def doEverythingRxn(flag: BooleanRef): Rxn[String] = {
    for { // FlatMap
      // TicketRead:
      ticket <- Rxn.unsafe.ticketRead(ref2)
      // Lift, AlwaysRetry:
      retry <- Rxn.unsafe.delay { flag.elem }
      _ <- if (retry) {
        Rxn.unsafe.delay { flag.elem = false } *> Rxn.unsafe.retry[Unit]
      } else {
        Rxn.unit
      }
      // PostCommit, Choice:
      _ <- Rxn.postCommit(Rxn.unsafe.retry + Rxn.unit)
      // RefGetAxn, Map:
      v <- ref1.get.map(_ + "x").map(_.length + 42).map(_.toString)
      // Map2, UpdBase, Pure:
      i <- ((ref1.update { s => (s.## + 1).toString }).map2(Rxn.pure("42")) { (_, i) => i }).map2(ref2.get) { (x, y) =>
        s"${x}; ${y}"
      }.map2(Rxn.unsafe.delay { flag.elem = false }) { (x, _) => x }
      _ <- ref1.updateAndGet(_ + "x")
      _ <- ref1.modify { s => (s.substring(0, s.length - 1), 42) }
      _ <- ref1.set("xyz")
      // TicketWrite:
      _ <- ticket.unsafeSet(ticket.unsafePeek.##.toString)
      // DirectRead, AndAlso, Ctx:
      w <- (Rxn.unsafe.directRead(ref1) * Rxn.unsafe.delayContext { _.## })
      _ <- Rxn.unsafe.delayContext(_.toString) * Rxn.unsafe.delayContext(_.random.##)
      c <- Rxn.unsafe.delayContext2 { (_, is) => is.## } * Rxn.unsafe.delayContext2 { (c, _) => c.toString }
      _ <- Rxn.unsafe.delayContext2 { (c, i) => c.## ^ i.## }
      u <- Rxn.unsafe.embedUnsafe { implicit ir =>
        unsafe.getAndSetRef(ref2, "boo")
      } <* Rxn.unsafe.embedUnsafe { implicit ir => unsafe.addPostCommit(Rxn.unit) } *> Rxn.unsafe.embedUnsafe { _ => 42 }
      // As, ForceValidate:
      nn <- Rxn.unsafe.forceValidate.as("foo").as("bar").as(42).as(99)
      // ProductR, Flatten:
      foo <- ref2.set("err") *> Rxn.pure(Rxn.pure("foo")).flatten
      // TailRecM:
      ok <- Rxn.tailRecM(0) { n =>
        if (n < 1) Rxn.pure(Left(1))
        else Rxn.pure(Right("ok"))
      }
      _ <- Rxn.tailRecM(0) { _ => Rxn.pure(Right(())) }
    } yield s"${v}, ${i}, ${w._1}, ${c}, ${u}, ${nn}, ${foo}, ${ok}"
    // Missing: Unread, TentativeRead, Exchanger-related things, embedRxn-related things
  }

  private[this] final def doEverythingF(flag: BooleanRef): F[String] = {
    import cats.syntax.all._
    val F = this.syncF
    for { // FlatMap
      // Pure, Map:
      i <- F.pure(42).map(_ + 1).map(_ - 1).map(_.toString + "foo").map(_.substring(2))
      // Error, Attempt:
      _ <- F.raiseError[Unit](new Exception).attempt
      // Delay:
      b <- F.delay(flag.elem)
      _ <- F.delay(flag.elem.##) *> F.delay(i)
      // RealTime:
      t <- F.realTime
      // Monotonic:
      tt <- F.monotonic
      // HandleErrorWith:
      _ <- F.pure(42).handleErrorWith { _ => F.raiseError(new Exception) }.handleErrorWith { _ =>
        F.pure(99)
      }.handleError(_ => 42)
      // OnCancel, Uncancelable:
      _ <- F.uncancelable { _ =>
        F.uncancelable { poll => F.onCancel(poll(F.pure("")), F.unit) }
      }
      _ <- F.uncancelable(_ => F.unit)
      // Blocking:
      _ <- F.blocking { flag.elem = false }
      _ <- F.blocking { flag.elem = true }
      _ <- F.blocking { flag.elem = b }
    } yield s"${i}, ${b}, ${t}, ${tt}"
    // Missing: Async-related things, Cancel, IOLocal, tracing-related things, ReadRT
  }
}

object ExtraWarmup {

  @State(Scope.Benchmark)
  class ForCe
    extends ExtraWarmup[IO]
    with GenMcasImplStateBase.ForIO
}
