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
package bench
package util

import cats.effect.IO

import munit.CatsEffectSuite

import org.openjdk.jmh.results.ScalarResult

final class RxnProfilerSpecIO
  extends BaseSpecIO
  with SpecEMCAS
  with RxnProfilerSpec[IO]

trait RxnProfilerSpec[F[_]] extends CatsEffectSuite with BaseSpecAsyncF[F] { this: KCASImplSpec =>

  def simulateStart: F[RxnProfiler] = F.delay {
    val p = new RxnProfiler
    p.beforeIteration(null, null) // TODO: nulls
    p
  }

  def simulateEnd(p: RxnProfiler): F[Map[String, ScalarResult]] = F.delay {
    import scala.jdk.CollectionConverters._
    val rss = p.afterIteration(null, null, null) // TODO: nulls
    Map(rss.asScala.toList.map { r =>
      (r.getLabel -> r.asInstanceOf[ScalarResult])
    }: _*)
  }

  test("RxnProfiler.profiledExchanger") {
    for {
      e <- RxnProfiler.profiledExchanger[String, Int].run[F]
      p <- simulateStart
      fib <- e.exchange[F]("foo").start
      _ <- assertResultF(e.dual.exchange[F](42), "foo")
      _ <- assertResultF(fib.joinWithNever, 42)
      r <- simulateEnd(p)
      _ <- assertEqualsF(r(RxnProfiler.Exchanges).getScore, 1.0)
    } yield ()
  }
}
