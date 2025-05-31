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
package unsafe

import cats.effect.{ IO, SyncIO }

import munit.CatsEffectSuite

import core.{ Ref, Reactive }

final class InteropSpec extends CatsEffectSuite with MUnitUtils {

  private[this] val _reactiveFromDefaultUnsafeApi: (Reactive[IO], SyncIO[Unit]) =
    Reactive.fromIn[SyncIO, IO](api.unsafeRuntime).allocated.unsafeRunSync()

  private[this] val _customRuntime =
    ChoamRuntime.unsafeBlocking()

  private[this] val _reactiveFromCustomRuntime =
    Reactive.fromIn[SyncIO, IO](_customRuntime).allocated.unsafeRunSync()

  private[this] val _unsafeApiFromCustomRuntime =
    new UnsafeApi(_customRuntime) {}

  final override def afterAll(): Unit = {
    _reactiveFromDefaultUnsafeApi._2.unsafeRunSync()
    _reactiveFromCustomRuntime._2.unsafeRunSync()
    _customRuntime.unsafeCloseBlocking()
    super.afterAll()
  }

  interopTests("Default UnsafeApi", api)(using _reactiveFromDefaultUnsafeApi._1)
  interopTests("Custom UnsafeApi", _unsafeApiFromCustomRuntime)(using _reactiveFromCustomRuntime._1)

  def interopTests(prefix: String, api: UnsafeApi)(implicit F: Reactive[IO]): Unit = {

    import api._

    test(s"$prefix - Create with Rxn, use imperatively") {
      Ref(42).run[IO].flatMap { ref =>
        IO {
          atomically { implicit ir =>
            assertEquals(ref.value, 42)
            ref.value = 99
            assertEquals(ref.value, 99)
          }
        } *> ref.get.run[IO].flatMap { v =>
          IO(assertEquals(v, 99))
        }
      }
    }

    test(s"$prefix - Create imperatively, use with Rxn") {
      IO(newRef(42)).flatMap { ref =>
        ref.getAndUpdate(_ + 1).run[IO].flatMap { r =>
          IO(assertEquals(r, 42)) *> ref.get.run[IO].flatMap { v =>
            IO(assertEquals(v, 43)) *> IO {
              assertEquals(atomically(readRef(ref)(_)), 43)
            }
          }
        }
      }
    }
  }
}
