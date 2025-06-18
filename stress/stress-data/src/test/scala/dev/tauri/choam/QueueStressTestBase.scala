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

import cats.effect.SyncIO

import internal.mcas.Mcas
import core.Reactive
import data.{ Queue, QueueHelper, RemoveQueue }

abstract class QueueStressTestBase extends StressTestBase {

  protected def newQueue[A](as: A*): Queue[A]

  protected final override def impl: Mcas =
    QueueStressTestBase._mcasImpl

  protected final implicit def reactive: Reactive[SyncIO] =
    QueueStressTestBase._reactiveForSyncIo
}

private object QueueStressTestBase {

  private val _reactiveForSyncIo: Reactive[SyncIO] = {
    Reactive.forSyncIn[SyncIO, SyncIO].allocated.unsafeRunSync()._1
  }

  private val _mcasImpl: Mcas = {
    this._reactiveForSyncIo.mcasImpl
  }
}

abstract class MsQueueStressTestBase extends QueueStressTestBase {
  protected final override def newQueue[A](as: A*): Queue[A] =
    QueueHelper.msQueueFromList[SyncIO, A](as.toList).unsafeRunSync()
}

abstract class RemoveQueueStressTestBase extends QueueStressTestBase {
  protected final override def newQueue[A](as: A*): RemoveQueue[A] =
    QueueHelper.fromList[SyncIO, RemoveQueue, A](RemoveQueue[A])(as.toList).unsafeRunSync()
}
