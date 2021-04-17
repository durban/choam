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
package stream

import cats.effect.IO

import async.AsyncQueue

final class StreamSpec_Prim_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with StreamSpecPrim[IO]

final class StreamSpec_Derived_EMCAS_IO
  extends BaseSpecIO
  with SpecEMCAS
  with StreamSpecDerived[IO]

sealed trait StreamSpecDerived[F[_]] extends StreamSpec[F] { this: KCASImplSpec =>
  final override def newAsyncQueue[A]: F[AsyncQueue[F, A]] =
    AsyncQueue.derived[F, A].run[F]
}

sealed trait StreamSpecPrim[F[_]] extends StreamSpec[F] { this: KCASImplSpec =>
  final override def newAsyncQueue[A]: F[AsyncQueue[F, A]] =
    AsyncQueue.primitive[F, A].run[F]
}

sealed trait StreamSpec[F[_]] extends BaseSpecAsyncF[F] { this: KCASImplSpec =>

  def newAsyncQueue[A]: F[AsyncQueue[F, A]]

  test("AsyncQueue to stream") {
    for {
      q <- newAsyncQueue[String]
      fibVec <- q.stream.take(8).compile.toVector.start
      _ <- (1 to 10).toList.traverse { idx => q.enqueue[F](idx.toString) }
      _ <- assertResultF(fibVec.joinWithNever, (1 to 8).map(_.toString).toVector)
      _ <- assertResultF(q.deque, "9")
      _ <- assertResultF(q.deque, "10")
    } yield ()
  }
}
