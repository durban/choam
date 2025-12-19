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

import java.util.concurrent.atomic.AtomicLong
import java.io.{ PrintStream, IOException }

import internal.mcas.OsRng

/**
 * Writes data form `OsRng` to stdout in a format
 * which is suitable for `dieharder`.
 *
 * How to use (JVM):
 * - `sbt stressRng/stage`
 * - `stress/stress-rng/jvm/target/universal/stage/bin/choam-stress-rng | dieharder -a -g 200`
 *   (or `-d N` instead of `-a` to only run test no. `N`)
 *
 * How to use (SN):
 * - `sbt stressRngNative/nativeLink`
 * - `stress/stress-rng/native/target/scala-2.13/choam-stress-rng | dieharder -a -g 200`
 *
 * How to use (JS); (TODO: crashes with OOM?):
 * - `js stressRngJS/fastLinkJS`
 * - `node stress/stress-rng/js/target/scala-2.13/choam-stress-rng-fastopt/main.js | dieharder -a -g 200`
 */
object OsRngDieharder extends OsRngDieharderPlatform {

  private final val bufferSize = 4 * 1024

  final def main(args: Array[String]): Unit = {
    val rng = OsRng.mkNew()
    val out = System.out
    val err = System.err
    // we also want to test using
    // `OsRng` from multiple
    // threads:
    val nProc = args match {
      case Array() =>
        Runtime.getRuntime().availableProcessors()
      case Array(h, _*) =>
        val n = h.toInt
        if (n > 0) n else throw new IllegalArgumentException
      case x =>
        throw new IllegalArgumentException(s"unexpected arguments: ${x.mkString(", ")}")
    }
    if (nProc > 1) {
      err.println(s"Starting ${nProc} threads...")
      val threads = (1 to nProc).map { idx =>
        this.startThread(() => { writeMany(idx, rng, out, err) })
      }
      threads.foreach { t => this.joinThread(t) }
    } else {
      writeMany(0, rng, out, err)
    }
  }

  private final def writeMany(idx: Int, rng: OsRng, out: PrintStream, err: PrintStream): Unit = {
    val ctr = new AtomicLong
    val buff = new Array[Byte](bufferSize)
    try {
      while (true) {
        writeOne(rng, buff, out, ctr)
      }
    } finally {
      val count = ctr.get()
      err.println(s"Thread ${idx} done, written ${count} blocks of ${bufferSize} bytes")
    }
  }

  private final def writeOne(rng: OsRng, buff: Array[Byte], out: PrintStream, ctr: AtomicLong): Unit = {
    rng.nextBytes(buff)
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
