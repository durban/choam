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
package core

import cats.effect.kernel.{ Sync, Resource }

import internal.mcas.{ Mcas, OsRng }

sealed trait ChoamRuntime {

  private[choam] def mcasImpl: Mcas

  private[choam] def unsafeCloseBlocking(): Unit
}

object ChoamRuntime {

  private[this] final class ChoamRuntimeImpl private[ChoamRuntime] (
    private[choam] final override val mcasImpl: Mcas,
    osRng: OsRng,
  ) extends ChoamRuntime {

    private[choam] final override def unsafeCloseBlocking(): Unit = {
      this.mcasImpl.close()
      this.osRng.close()
    }
  }

  final def apply[F[_]](implicit F: Sync[F]): Resource[F, ChoamRuntime] = {
    Resource.make(F.blocking { this.unsafeBlocking() }) { rt =>
      F.blocking { rt.unsafeCloseBlocking() }
    }
  }

  /** Acquires resources, allocates a new runtime; may block! */
  final def unsafeBlocking(): ChoamRuntime = {
    val o = OsRng.mkNew() // may block due to /dev/random
    val m = Mcas.newDefaultMcas(o) // may block due to JMX
    new ChoamRuntimeImpl(m, o)
  }
}
