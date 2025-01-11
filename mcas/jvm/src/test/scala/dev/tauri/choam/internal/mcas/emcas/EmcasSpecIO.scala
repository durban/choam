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
package mcas
package emcas

import cats.syntax.all._
import cats.effect.{ Async, IO }

// TODO: this probably should be a JCStress test

final class EmcasSpecIO extends EmcasSpecF[IO] {

  final val N = 256

  final def testF(name: String)(body: Async[IO] => IO[Any])(implicit loc: munit.Location): Unit = {
    super.test(name) {
      val tsk: IO[Any] = body.apply(IO.asyncForIO).replicateA_(N)
      tsk.unsafeRunSync()(cats.effect.unsafe.IORuntime.global)
    }
  }
}

abstract class EmcasSpecF[F[_]] extends BaseSpec {

  protected[this] val inst: Emcas =
    new Emcas(this.osRngInstance)

  protected def testF(name: String)(body: Async[F] => F[Any])(implicit loc: munit.Location): Unit

  private[this] def sub(s: String): String =
    s.substring(1)

  testF("EMCAS sharing commit-ts (disjoint)") { implicit F =>
    for {
      r1 <- F.delay(MemoryLocation.unsafe("-a"))
      r2 <- F.delay(MemoryLocation.unsafe("-b"))
      r3 <- F.delay(MemoryLocation.unsafe("-c"))
      r4 <- F.delay(MemoryLocation.unsafe("-d"))
      // initialize them (a, b, c, d):
      _ <- F.delay {
        val ok = inst
          .currentContext()
          .builder()
          .updateRef(r1, sub)
          .updateRef(r2, sub)
          .updateRef(r3, sub)
          .updateRef(r4, sub)
          .tryPerformOk()
        assert(ok)
      }
      // 2 racing disjoint updates:
      txn1 = F.delay {
        val ok = inst
          .currentContext()
          .builder()
          .updateRef(r1, (_: String) + "a")
          .updateRef(r2, (_: String) + "b")
          .tryPerformOk()
        assert(ok)
      }
      txn2 = F.delay {
        val ok = inst
          .currentContext()
          .builder()
          .updateRef(r3, (_: String) + "c")
          .updateRef(r4, (_: String) + "d")
          .tryPerformOk()
        assert(ok)
      }
      _ <- F.both(F.cede *> txn1, F.cede *> txn2)
      _ <- F.delay {
        val ctx = inst.currentContext()
        assertEquals(ctx.readDirect(r1), "aa")
        assertEquals(ctx.readDirect(r2), "bb")
        assertEquals(ctx.readDirect(r3), "cc")
        assertEquals(ctx.readDirect(r4), "dd")
        val r1Ver = ctx.readVersion(r1)
        val r2Ver = ctx.readVersion(r2)
        val r3Ver = ctx.readVersion(r3)
        val r4Ver = ctx.readVersion(r4)
        assertEquals(r1Ver, r2Ver)
        assertEquals(r3Ver, r4Ver)
        // the difference between the 2 commit-ts must be <= 1
        if (r1Ver > r3Ver) {
          assertEquals(r1Ver - r3Ver, 1L)
        } else if (r3Ver > r1Ver) {
          assertEquals(r3Ver - r1Ver, 1L)
        } // else: ok, they're equal
      }
    } yield ()
  }

  testF("EMCAS sharing commit-ts (real conflict)") { implicit F =>
    for {
      r1 <- F.delay(MemoryLocation.unsafe("-a"))
      r2 <- F.delay(MemoryLocation.unsafe("-b"))
      r3 <- F.delay(MemoryLocation.unsafe("-c"))
      // initialize them (a, b, c):
      _ <- F.delay {
        val ok = inst
          .currentContext()
          .builder()
          .updateRef(r1, sub)
          .updateRef(r2, sub)
          .updateRef(r3, sub)
          .tryPerformOk()
        assert(ok)
      }
      // 2 racing conflicting updates:
      txn1 = F.delay {
        inst
          .currentContext()
          .builder()
          .updateRef(r1, (_: String) + "a1")
          .updateRef(r2, (_: String) + "b")
          .tryPerformOk()
      }
      txn2 = F.delay {
        inst
          .currentContext()
          .builder()
          .updateRef(r1, (_: String) + "a2")
          .updateRef(r3, (_: String) + "c")
          .tryPerformOk()
      }
      ok1ok2 <- F.both(F.cede *> txn1, F.cede *> txn2)
      (ok1, ok2) = ok1ok2
      _ <- F.delay {
        val ctx = inst.currentContext()
        val v1 = ctx.readDirect(r1)
        val r1Ver = ctx.readVersion(r1)
        val r2Ver = ctx.readVersion(r2)
        val r3Ver = ctx.readVersion(r3)
        if (ok1 && ok2) {
          // both succeeded, these must be
          // at most 2 difference between
          // the versions (when 2 threads
          // race to get a fresh version
          // for the winner):
          assert((v1 == "aa1a2") || (v1 == "aa2a1"))
          if (v1 == "aa1a2") {
            assertEquals(r1Ver, r3Ver)
            assert(r3Ver > r2Ver)
            assert((r3Ver - r2Ver) <= 2L)
          } else { // "aa2a1"
            assertEquals(r1Ver, r2Ver)
            assert(r2Ver > r3Ver)
            assert((r2Ver - r3Ver) <= 2L)
          }
          assertEquals(ctx.readDirect(r2), "bb")
          assertEquals(ctx.readDirect(r3), "cc")
        } else if (ok1) {
          assertEquals(v1, "aa1")
          assertEquals(ctx.readDirect(r2), "bb")
          assertEquals(ctx.readDirect(r3), "c")
        } else if (ok2) {
          assertEquals(v1, "aa2")
          assertEquals(ctx.readDirect(r2), "b")
          assertEquals(ctx.readDirect(r3), "cc")
        } else {
          fail("none was successful")
        }
      }
    } yield ()
  }
}
