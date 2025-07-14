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
import java.util.concurrent.CountDownLatch

import cats.effect.IO

final class LazyIdempotentSpecJvm extends munit.CatsEffectSuite {

  test("race") {
    for {
      ctr <- IO(new AtomicInteger(0))
      cleanupLog <- IO(new AtomicReference(List.empty[AnyRef]))
      cdl <- IO(new CountDownLatch(2))
      lzy <- IO(LazyIdempotent.makeFull[AnyRef](
        () => {
          ctr.incrementAndGet()
          cdl.countDown()
          cdl.await()
          new AnyRef
        },
        { x => cleanupLog.getAndUpdate(x :: _); () },
      ))
      rr <- IO.both(IO(lzy.get()), IO(lzy.get()))
      _ <- IO {
        assert(rr._1 eq rr._2)
        assertEquals(ctr.get(), 2)
        cleanupLog.get() match {
          case h :: Nil =>
            assert(h ne rr._1)
          case log =>
            fail(s"unexpected: $log")
        }
      }
    } yield ()
  }
}
