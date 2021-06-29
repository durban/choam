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


  private[choam] final class ReactionData private (
    val postCommit: List[Axn[Unit]],
    val exchangerData: Exchanger.StatMap
  ) {

    def withPostCommit(act: Axn[Unit]): ReactionData = {
      ReactionData(
        postCommit = act :: this.postCommit,
        exchangerData = this.exchangerData
      )
    }
  }

  private[choam] final object ReactionData {
    def apply(
      postCommit: List[Axn[Unit]],
      exchangerData: Exchanger.StatMap
    ): ReactionData = {
      new ReactionData(postCommit, exchangerData)
    }
  }

/*

  private sealed abstract class GenExchange[A, B, C, D, E](
    val exchanger: Exchanger[A, B],
    val k: Rxn[D, E]
  ) extends Rxn[C, E] { self =>

    private[choam] def tag = 10

    protected def transform1(c: C): A

    protected def transform2(b: B, c: C): D

    protected final override def tryPerform(n: Int, c: C, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): TentativeResult[E] = {
      this.tryExchange(c, rd, desc, ctx) match {
        case Right(contMsg) =>
          // println(s"exchange happened, got ${contMsg} - thread#${Thread.currentThread().getId()}")
          // TODO: this way we lose exchanger statistics if we start a new reaction
          maybeJump(n, (), contMsg.cont, contMsg.rd, contMsg.desc, ctx)
        case Left(_) =>
          // TODO: pass back these stats to the main loop
          Retry
      }
    }

    private[Rxn] def tryExchange(c: C, rd: ReactionData, desc: EMCASDescriptor, ctx: ThreadContext): Either[Exchanger.StatMap, Exchanger.Msg[Unit, Unit, E]] = {
      val msg = Exchanger.Msg[A, B, E](
        value = transform1(c),
        cont = k.lmap[B](b => self.transform2(b, c)),
        rd = rd,
        desc = desc // TODO: not threadsafe
      )
      // TODO: An `Exchange(...) + Exchange(...)` should post the
      // TODO: same offer to both exchangers, so that fulfillers
      // TODO: can race there.
      this.exchanger.tryExchange(msg, ctx)
    }

    protected final override def andThenImpl[F](that: Rxn[E, F]): Rxn[C, F] = {
      new GenExchange[A, B, C, D, F](this.exchanger, this.k >>> that) {
        protected override def transform1(c: C): A =
          self.transform1(c)
        protected override def transform2(b: B, c: C): D =
          self.transform2(b, c)
      }
    }

    protected final override def productImpl[F, G](that: Rxn[F, G]): Rxn[(C, F), (E, G)] = {
      new GenExchange[A, B, (C, F), (D, F), (E, G)](exchanger, k.productImpl(that)) {
        protected override def transform1(cf: (C, F)): A =
          self.transform1(cf._1)
        protected override def transform2(b: B, cf: (C, F)): (D, F) = {
          val d = self.transform2(b, cf._1)
          (d, cf._2)
        }
      }
    }

    protected[choam] final override def firstImpl[F]: Rxn[(C, F), (E, F)] = {
      new GenExchange[A, B, (C, F), (D, F), (E, F)](exchanger, k.firstImpl[F]) {
        protected override def transform1(cf: (C, F)): A =
          self.transform1(cf._1)
        protected override def transform2(b: B, cf: (C, F)): (D, F) = {
          val d = self.transform2(b, cf._1)
          (d, cf._2)
        }
      }
    }

    final override def toString: String =
      s"GenExchange(${exchanger}, ${k})"
  }




        case 10 => // GenExchange
          val c = curr.asInstanceOf[GenExchange[ForSome.x, ForSome.y, A, ForSome.z, R]]
          val rd = ReactionData(
            postCommit = postCommit.toArray().toList,
            exchangerData = stats
          )
          c.tryExchange(a, rd, desc, ctx) match {
            case Left(newStats) =>
              stats = newStats
              if (altA.isEmpty) {
                reset()
                loop(rxn, x, retries + 1, spin = true)
              } else {
                desc = popPcAndSnap()
                loop(altK.pop(), altA.pop(), retries + 1, spin = false)
              }
            case Right(contMsg) =>
              desc = contMsg.desc
              postCommit.clear()
              postCommit.pushAll(contMsg.rd.postCommit)
              loop(contMsg.cont, (), retries, spin = false)
          }

*/
