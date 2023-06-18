/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2023 Daniel Urban and contributors listed in NOTICE.txt
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

import java.util.concurrent.atomic.AtomicLong
import java.io.{ PrintStream, IOException }

import cats.syntax.all._
import cats.effect.{ IO, IOApp, ExitCode }

import random.OsRng

/**
 * Writes data form `OsRng` to stdout in a format
 * which is suitable for `dieharder`.
 *
 * How to use:
 * - `sbt stressRng/stage`
 * - `stress-rng/target/universal/stage/bin/choam-stress-rng | dieharder -a -g 200`
 *   (or `-d N` instead of `-a` to only run test no. `N`)
 */
object OsRngDieharder extends IOApp {

  final val bufferSize = 4 * 1024

  final override def run(args: List[String]): IO[ExitCode] = {
    IO.blocking { OsRng.mkNew() }.flatMap { rng =>
      IO { System.out }.flatMap { out =>
        // we also want to test using
        // `OsRandom` from multiple
        // threads, so we start N
        // independent fibers:
        val getN = args match {
          case Nil =>
            IO { Runtime.getRuntime().availableProcessors() }
          case h :: _ =>
            IO {
              val n = h.toInt
              if (n > 0) n else throw new IllegalArgumentException
            }
        }
        getN.flatMap { N =>
          IO.consoleForIO.errorln(s"Starting $N fibers...").flatMap { _ =>
            (1 to N).toList.parTraverse { idx =>
              writeMany(idx, rng, out)
            }.void.voidError.as(ExitCode.Success)
          }
        }
      }
    }
  }

  def writeMany(idx: Int, rng: OsRng, out: PrintStream): IO[Nothing] = {
    IO { new AtomicLong }.flatMap { ctr =>
      IO { new Array[Byte](bufferSize) }.flatMap { buff =>
        (writeOne(rng, buff, out, ctr) >> IO.cede).foreverM
      }.guarantee(IO { ctr.get() }.flatMap { count =>
        IO.consoleForIO.errorln(s"Fiber ${idx} done, written ${count} blocks of ${bufferSize} bytes")
      })
    }
  }

  def writeOne(rng: OsRng, buff: Array[Byte], out: PrintStream, ctr: AtomicLong): IO[Unit] = {
    IO { rng.nextBytes(buff) } >> IO {
      out.write(buff)
      // `PrintStream` swallows errors, but we need
      // them here, because `dieharder` will close
      // the pipe, and we need to exit:
      if (out.checkError()) {
        throw new IOException
      } else {
        ctr.incrementAndGet()
        ()
      }
    }
  }
}
