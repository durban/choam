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

import cats.effect.SyncIO

final class MichaelScottQueueSpec_NaiveKCAS_SyncIO
  extends BaseSpecSyncIO
  with MichaelScottQueueSpec[SyncIO]
  with SpecNaiveKCAS

final class MichaelScottQueueSpec_EMCAS_SyncIO
  extends BaseSpecSyncIO
  with MichaelScottQueueSpec[SyncIO]
  with SpecEMCAS

trait MichaelScottQueueSpec[F[_]] extends BaseSpecSyncF[F] { this: KCASImplSpec =>

  test("MichaelScottQueue should include the elements passed to its constructor") {
    assertResultF(new MichaelScottQueue[Int]().unsafeToList[F], Nil)
    assertResultF(new MichaelScottQueue[Int](1 :: 2 :: 3 :: Nil).unsafeToList[F], 1 :: 2 :: 3 :: Nil)
  }

  test("MichaelScottQueue transfer") {
    val q1 = new MichaelScottQueue[Int](1 :: 2 :: 3 :: Nil)
    val q2 = new MichaelScottQueue[Int](List.empty[Int])
    val r = q1.tryDeque.map(_.getOrElse(0)) >>> q2.enqueue
    for {
      _ <- r.run[F]
      _ <- r.run[F]
      _ <- assertResultF(q1.unsafeToList[F], List(3))
      _ <- assertResultF(q2.unsafeToList[F], List(1, 2))
    } yield ()
  }
}
