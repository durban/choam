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
package stream

import java.util.concurrent.ThreadLocalRandom

import scala.concurrent.duration._

import cats.effect.IO

final class PubSubSpec_DefaultMcas_IO
  extends BaseSpecIO
  with SpecDefaultMcas
  with PubSubSpec[IO]

final class PubSubSpec_DefaultMcas_ZIO
  extends BaseSpecZIO
  with SpecDefaultMcas
  with PubSubSpec[zio.Task]

trait PubSubSpec[F[_]]
  extends BaseSpecAsyncF[F]
  with async.AsyncReactiveSpec[F] { this: McasImplSpec =>

  final override def munitTimeout: Duration =
    2 * super.munitTimeout

  private[this] final val BS = 1024

  commonTests("DropOldest", PubSub.OverflowStrategy.dropOldest(BS))
  commonTests("DropNewest", PubSub.OverflowStrategy.dropNewest(BS))
  commonTests("Unbounded", PubSub.OverflowStrategy.unbounded)
  commonTests("Backpressure", PubSub.OverflowStrategy.backpressure(BS))

  private def commonTests(name: String, str: PubSub.OverflowStrategy): Unit = {

    def checkOrder(v: Vector[Int]): F[Unit] = F.delay {
      val pos = v.filter(_ > 0)
      pos.sliding(2).foreach {
        case Vector(i, j) => assert(i < j)
        case x => fail(s"unexpected: $x")
      }
      val neg = v.filter(_ < 0)
      neg.sliding(2).foreach {
        case Vector(i, j) => assert(i > j)
        case x => fail(s"unexpected: $x")
      }
    }

    test(s"$name - racing publishers (bufferSize = $BS)") {
      val N = BS / 2
      val nums = (1 to N).toVector
      val expSet = (nums.toSet ++ nums.map(-_).toSet)
      val succVec = Vector.fill(N)(PubSub.Success)
      val t = for {
        hub <- PubSub[F, Int](str).run[F]
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribe.compile.toVector.start
        _ <- F.sleep(1.second) // wait for subscription to happen
        rr <- F.both(
          F.cede *> nums.traverse(i => hub.publish(i).run[F]),
          F.cede *> nums.traverse(i => hub.publish(-i).run[F]),
        )
        _ <- assertEqualsF(rr._1, succVec)
        _ <- assertEqualsF(rr._2, succVec)
        _ <- assertResultF(hub.close.run[F], PubSub.Backpressured) // with high probability
        v1 <- f1.joinWithNever
        v2 <- f2.joinWithNever
        _ <- assertEqualsF(v1, v2)
        _ <- assertEqualsF(v1.toSet, expSet)
        _ <- checkOrder(v1)
      } yield ()
      t.replicateA_(if (isJs()) 1 else 5)
    }

    test(s"$name - racing publishers (bufferSize = 1)") {
      val N = 512
      val nums = (1 to N).toVector
      val t = for {
        hub <- PubSub[F, Int](str).run[F]
        f1 <- hub.subscribe.evalTap { _ => if (ThreadLocalRandom.current().nextBoolean()) F.sleep(1.milli) else F.unit }.compile.toVector.start
        f2 <- hub.subscribe.compile.toVector.start
        _ <- F.sleep(1.second) // wait for subscription to happen
        _ <- F.both(
          F.cede *> nums.traverse_(i => hub.publish(i).run[F]),
          F.cede *> nums.traverse_(i => hub.publish(-i).run[F]),
        )
        closeRes <- hub.close.run[F]
        _ <- assertF((closeRes eq PubSub.Backpressured) || (closeRes eq PubSub.Success))
        v1 <- f1.joinWithNever
        v2 <- f2.joinWithNever
        _ <- assertEqualsF(v1, v2) // publish is all or nothing => subscribers must see the same items
        _ <- checkOrder(v1) // we may lose items, but the order must be correct
      } yield ()
      t.replicateA_(if (isJs()) 1 else 5)
    }
  }
}
