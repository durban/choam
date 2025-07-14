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
package internal

import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }

import cats.effect.IO

final class LazyIdempotentSpec extends munit.CatsEffectSuite {

  test("simple") {
    for {
      ctr <- IO(new AtomicInteger(0))
      lzy <- IO(LazyIdempotent.make { ctr.incrementAndGet(); new AnyRef })
      rr <- IO.both(IO(lzy.get()), IO(lzy.get()))
      _ <- IO {
        assert(rr._1 eq rr._2)
        val count = ctr.get()
        assert(count <= 2)
        assert(count >= 1)
      }
    } yield ()
  }

  test("full") {
    for {
      ctr <- IO(new AtomicInteger(0))
      cleanupLog <- IO(new AtomicReference(List.empty[AnyRef]))
      lzy <- IO(LazyIdempotent.makeFull[AnyRef](
        () => { ctr.incrementAndGet(); new AnyRef },
        { x => cleanupLog.getAndUpdate(x :: _); () },
      ))
      rr <- IO.both(IO(lzy.get()), IO(lzy.get()))
      _ <- IO {
        assert(rr._1 eq rr._2)
        val count = ctr.get()
        assert(count <= 2)
        assert(count >= 1)
        cleanupLog.get() match {
          case Nil =>
            ()
          case h :: Nil =>
            assert(h ne rr._1)
          case log =>
            fail(s"unexpected: $log")
        }
      }
    } yield ()
  }
}
