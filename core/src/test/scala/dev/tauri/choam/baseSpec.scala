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

import scala.concurrent.ExecutionContext

import cats.effect.{ IO, IOApp, Timer, ContextShift, Blocker }

import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.scalatest.{ Suite, BeforeAndAfterAll }
import org.scalactic.TypeCheckedTripleEquals
import cats.effect.ExitCode

trait KCASImplSpec {
  implicit def kcasImpl: kcas.KCAS
}

trait BaseSpec
  extends KCASImplSpec
  with AnyFlatSpecLike
  with Matchers
  with TypeCheckedTripleEquals
  with IOSpec {

  // TODO: try to remove this (or make it non-implicit)
  implicit val ec: ExecutionContext =
    ExecutionContext.global
}

trait IOSpec extends BeforeAndAfterAll { this: Suite =>

  private[this] object ioApp extends IOApp {
    val publicTimer = this.timer
    val publicContextShift = this.contextShift
    override def run(args: List[String]): IO[ExitCode] = IO.pure(ExitCode.Success)
  }

  implicit val timerIo: Timer[IO] =
    ioApp.publicTimer

  implicit val contextShiftIo: ContextShift[IO] =
    ioApp.publicContextShift

  private[this] val blockerAndClose: (Blocker, IO[Unit]) =
    Blocker.apply[IO].allocated.unsafeRunSync()

  def blocker: Blocker =
    blockerAndClose._1

  override protected def afterAll(): Unit = {
    blockerAndClose._2.unsafeRunSync()
    super.afterAll()
  }
}

trait SpecNaiveKCAS extends KCASImplSpec {
  implicit override def kcasImpl: kcas.KCAS =
    kcas.KCAS.NaiveKCAS
}

trait SpecMCAS extends KCASImplSpec {
  implicit override def kcasImpl: kcas.KCAS =
    kcas.KCAS.MCAS
}

trait SpecEMCAS extends KCASImplSpec {
  implicit override def kcasImpl: kcas.KCAS =
    kcas.KCAS.EMCAS
}
