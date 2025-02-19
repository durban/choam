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

sealed trait RxnRuntime { // TODO: maybe call it `ChoamRuntime`?
  private[choam] def mcasImpl: Mcas
}

object RxnRuntime {

  private[this] final class RxnRuntimeImpl private[RxnRuntime] (
    private[choam] final override val mcasImpl: Mcas,
  ) extends RxnRuntime

  final def apply[F[_]](implicit F: Sync[F]): Resource[F, RxnRuntime] = {
    defaultMcasResource[F].map(new RxnRuntimeImpl(_))
  }

  private[this] final def defaultMcasResource[F[_]](implicit F: Sync[F]): Resource[F, Mcas] = {
    osRngResource[F].flatMap { osRng =>
      Resource.make(
        acquire = F.blocking { // `blocking` because:
          // who knows what JMX is doing
          // when we're registering the mbean
          Mcas.newDefaultMcas(osRng)
         }
      )(
        release = { mcasImpl =>
          F.blocking { // `blocking` because:
            // who knows what JMX is doing
            // when we're unregistering the mbean
            mcasImpl.close()
          }
        }
      )
    }
  }

  private[this] final def osRngResource[F[_]](implicit F: Sync[F]): Resource[F, OsRng] = {
    Resource.make(
      acquire = F.blocking { // `blocking` because:
        OsRng.mkNew() // <- this call may block
      }
    )(
      release = { osRng =>
        F.blocking {  // `blocking` because:
          // closing the FileInputStream of
          // the OsRng involves a lock
          osRng.close()
        }
      }
    )
  }
}
