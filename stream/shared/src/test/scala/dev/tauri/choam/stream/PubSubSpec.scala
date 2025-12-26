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

import java.util.concurrent.{ ThreadLocalRandom, TimeoutException }

import scala.concurrent.duration._

import core.Rxn
import PubSub.OverflowStrategy

trait PubSubSpec[F[_]]
  extends BaseSpecAsyncF[F] { this: McasImplSpec =>

  protected[this] type H[A] <: PubSub.Simple[A]

  protected[this] def newHub[A](str: PubSub.OverflowStrategy): F[H[A]]

  final override def munitTimeout: Duration =
    2 * super.munitTimeout

  private[this] final val BS = if (this.isNative()) 512 else 1024

  commonTests("DropOldest", PubSub.OverflowStrategy.dropOldest(BS))
  commonTests("DropNewest", PubSub.OverflowStrategy.dropNewest(BS))
  commonTests("Unbounded", PubSub.OverflowStrategy.unbounded)
  commonTests("Backpressure", PubSub.OverflowStrategy.backpressure(BS))

  private def waitForSubscribers[A](hub: PubSub.Subscribe[A], expectedSubscribers: Int): F[Unit] = {
    val once: F[Unit] = hub.numberOfSubscriptions.run[F].flatMap { numSubs =>
      if (numSubs >= expectedSubscribers) F.unit
      else F.raiseError[Unit](new TimeoutException(s"only ${numSubs} subscribers instead of the expected ${expectedSubscribers}"))
    }
    fs2.Stream.retry(
      once,
      delay = 0.1.second,
      nextDelay = { d => d * 2 },
      maxAttempts = 7,
      retriable = _.isInstanceOf[TimeoutException],
    ).compile.onlyOrError
  }

  private def commonTests(name: String, str: PubSub.OverflowStrategy): Unit = {

    test(s"$name - racing publishers (bufferSize = $BS)") {
      val N = BS / 2
      val nums = (1 to N).toVector
      val expSet = (nums.toSet ++ nums.map(-_).toSet)
      val succVec = Vector.fill(N)(PubSub.Success)
      val t = for {
        hub <- newHub[Int](str)
        f1 <- hub.subscribe.compile.toVector.start
        f2 <- hub.subscribeWithInitial(str, Rxn.pure(0)).compile.toVector.start
        _ <- waitForSubscribers(hub, 2)
        rr <- F.both(
          F.cede *> nums.traverse(i => hub.emit(i).run[F]),
          F.cede *> nums.traverse(i => hub.emit(-i).run[F]),
        )
        _ <- assertEqualsF(rr._1, succVec)
        _ <- assertEqualsF(rr._2, succVec)
        closeRes <- hub.close.run[F]
        _ <- assertF((closeRes eq PubSub.Backpressured) || (closeRes eq PubSub.Success))
        v1 <- f1.joinWithNever
        v2WithInitial <- f2.joinWithNever
        _ <- assertEqualsF(v2WithInitial.take(1), Vector(0))
        v2 = v2WithInitial.drop(1)
        _ <- assertEqualsF(v1, v2)
        _ <- assertEqualsF(v1.toSet, expSet)
        _ <- checkOrder(v1, str)
      } yield ()
      val repeat = this.platform match {
        case Jvm => 50
        case Js => 1
        case Native => 25
      }
      t.replicateA_(repeat)
    }

    test(s"$name - racing publishers (bufferSize = 1)") {
      val N = if (this.isNative()) 256 else 512
      val nums = (1 to N).toVector
      val str2 = str.fold(
        unbounded = str, // can't set buffer size
        backpressure = _ => OverflowStrategy.backpressure(1),
        dropOldest = _ => OverflowStrategy.dropOldest(1),
        dropNewest = _ => OverflowStrategy.dropNewest(1),
      )
      val t = for {
        hub <- newHub[Int](str2)
        f1 <- hub.subscribe.evalTap { _ => if (ThreadLocalRandom.current().nextBoolean()) F.cede else F.unit }.compile.toVector.start
        f2 <- hub.subscribe.compile.toVector.start
        _ <- waitForSubscribers(hub, 2)
        _ <- F.both(
          F.cede *> nums.traverse_(i => hub.emit(i).run[F]),
          F.cede *> nums.traverse_(i => hub.emit(-i).run[F]),
        )
        closeRes <- hub.close.run[F]
        _ <- assertF((closeRes eq PubSub.Backpressured) || (closeRes eq PubSub.Success))
        v1 <- f1.joinWithNever
        v2 <- f2.joinWithNever
        _ <- str2.fold(
          unbounded = assertEqualsF(v1, v2) *> assertEqualsF(v1.toSet, nums.toSet union nums.map(-_).toSet), // we never lose items
          backpressure = _ => assertEqualsF(v1, v2), // if we lose an item, neither of them sees it
          dropOldest = _ => F.unit, // they might see different items (depending on scheduling)
          dropNewest = _ => F.unit, // they might see different items (depending on scheduling)
        )
        _ <- checkOrder(v1, str2) // we may lose items, but the order must be correct
        _ <- checkOrder(v2, str2) // we may lose items, but the order must be correct
      } yield ()
      val repeat = this.platform match {
        case Jvm => 50
        case Js => 1
        case Native => 25
      }
      t.replicateA_(repeat)
    }

    test(s"$name - subscribe/close race") {
      val t = for {
        hub <- newHub[Int](str)
        rr <- F.both(
          F.both(
            hub.subscribeWithInitial(str, Rxn.pure(1)).compile.toVector.start,
            hub.close.run,
          ),
          F.both(
            hub.subscribeWithInitial(str, Rxn.pure(2)).compile.toVector.start,
            hub.subscribeWithInitial(str, Rxn.pure(3)).compile.toVector.start,
          ),
        )
        ((fib1, closeResult), (fib2, fib3)) = rr
        _ <- if (closeResult eq PubSub.Backpressured) {
          hub.awaitShutdown
        } else {
          assertEqualsF(closeResult, PubSub.Success)
        }
        r1 <- fib1.joinWithNever
        r2 <- fib2.joinWithNever
        r3 <- fib3.joinWithNever
        _ <- assertF((clue(r1) == Vector()) || (r1 == Vector(1)))
        _ <- assertF((clue(r2) == Vector()) || (r2 == Vector(2)))
        _ <- assertF((clue(r3) == Vector()) || (r3 == Vector(3)))
      } yield ()
      val repeat = this.platform match {
        case Jvm => 500
        case Js => 1
        case Native => 250
      }
      t.replicateA_(repeat)
    }

    test(s"$name - subscribe/close/publish race") {
      val t = for {
        hub <- newHub[Int](str)
        rr <- F.both(
          F.both(
            hub.subscribeWithInitial(str, Rxn.pure(1)).compile.toVector.start,
            hub.emit(9).run,
          ),
          F.both(
            hub.subscribeWithInitial(str, Rxn.pure(2)).compile.toVector.start,
            F.cede *> hub.close.run,
          ),
        )
        ((fib1, emitResult), (fib2, closeResult)) = rr
        _ <- if (closeResult eq PubSub.Backpressured) {
          hub.awaitShutdown
        } else {
          assertEqualsF(closeResult, PubSub.Success)
        }
        r1 <- fib1.joinWithNever
        r2 <- fib2.joinWithNever
        _ <- if (emitResult eq PubSub.Closed) {
          for {
            _ <- assertF((clue(r1) == Vector()) || (r1 == Vector(1)))
            _ <- assertF((clue(r2) == Vector()) || (r2 == Vector(2)))
          } yield ()
        } else {
          for {
            _ <- assertEqualsF(emitResult, PubSub.Success)
            _ <- assertF((clue(r1) == Vector()) || (r1 == Vector(1)) || (r1 == Vector(1, 9)))
            _ <- assertF((clue(r2) == Vector()) || (r2 == Vector(2)) || (r2 == Vector(2, 9)))
          } yield ()
        }
      } yield ()
      val repeat = this.platform match {
        case Jvm => 50
        case Js => 1
        case Native => 25
      }
      t.replicateA_(repeat)
    }
  }

  private def checkOrder(v: Vector[Int], str2: PubSub.OverflowStrategy): F[Unit] = F.delay {
    val canLoseItems = str2.fold(
      unbounded = false,
      backpressure = { _ < BS },
      dropOldest = { _ < BS },
      dropNewest = { _ < BS },
    )
    val pos = v.filter(_ > 0)
    pos.sliding(2).foreach {
      case Vector(i, j) => assert(i < j)
      case _ => assert(canLoseItems)
    }
    val neg = v.filter(_ < 0)
    neg.sliding(2).foreach {
      case Vector(i, j) => assert(i > j)
      case _ => assert(canLoseItems)
    }
  }
}
