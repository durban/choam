/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2025 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.{ Executors, TimeUnit, TimeoutException }

import scala.concurrent.ExecutionContext

import cats.effect.kernel.Resource
import cats.effect.IO

import internal.mcas.Mcas

abstract class ChoamRuntimeImplSpecPlatform extends munit.CatsEffectSuite with BaseSpec {

  test("Thread-local ThreadContexts") {
    val ecRes = Resource.make(IO(Executors.newSingleThreadExecutor())) { ex =>
      IO.blocking {
        ex.shutdown()
        if (!ex.awaitTermination(1L, TimeUnit.SECONDS)) {
          throw new TimeoutException
        }
      }
    }.map(ExecutionContext.fromExecutorService)
    ecRes.use { ec =>
      def once(rt: ChoamRuntime): IO[(Mcas.ThreadContext, Mcas.ThreadContext)] = {
        IO(rt.mcasImpl.currentContext()).flatMap { ctx1a =>
          val t = for {
            _ <- assertIOBoolean(IO(!rt.mcasImpl.isCurrentContext(ctx1a)))
            ctx1b <- IO(rt.mcasImpl.currentContext())
            _ <- assertIOBoolean(IO(rt.mcasImpl.isCurrentContext(ctx1b)))
          } yield ctx1b
          t.evalOn(ec).map { ctx1b => (ctx1a, ctx1b) }
        }
      }
      for {
        ctx1ab <- ChoamRuntime.make[IO].use(once)
        (ctx1a, ctx1b) = ctx1ab
        ctx2ab <- ChoamRuntime.make[IO].use(once)
        (ctx2a, ctx2b) = ctx2ab
        _ <- assertIOBoolean(IO(ctx1a ne ctx1b))
        _ <- assertIOBoolean(IO(ctx1a ne ctx2a))
        _ <- assertIOBoolean(IO(ctx1a ne ctx2b))
        _ <- assertIOBoolean(IO(ctx1b ne ctx2a))
        _ <- assertIOBoolean(IO(ctx1b ne ctx2b))
        _ <- assertIOBoolean(IO(ctx2a ne ctx2b))
      } yield ()
    }
  }
}
