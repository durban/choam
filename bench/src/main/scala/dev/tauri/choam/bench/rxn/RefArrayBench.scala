/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2024 Daniel Urban and contributors listed in NOTICE.txt
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
package bench
package rxn

import java.util.concurrent.ThreadLocalRandom

import org.openjdk.jmh.annotations._

import internal.mcas.Mcas
import bench.util.RandomState
import RefArrayBench._

@Fork(2)
@Threads(2) // or multiples of 2
class RefArrayBench {

  @Benchmark
  @Group("strict_flat")
  def strict_flat_swap(st: StrictArrayState, r: RandomState): String = {
    st.swapAndGet(r)
  }

  @Benchmark
  @Group("strict_flat")
  def strict_flat_fold(st: StrictArrayState, r: RandomState): Int = {
    st.foldSparse(r)
  }

  @Benchmark
  @Group("lazy_flat")
  def lazy_flat_swap(st: SparseArrayState, r: RandomState): String = {
    st.swapAndGet(r)
  }

  @Benchmark
  @Group("lazy_flat")
  def lazy_flat_fold(st: SparseArrayState, r: RandomState): Int = {
    st.foldSparse(r)
  }

  @Benchmark
  @Group("strict_nonflat")
  def strict_nonflat_swap(st: StrictArrayOfRefsState, r: RandomState): String = {
    st.swapAndGet(r)
  }

  @Benchmark
  @Group("strict_nonflat")
  def strict_nonflat_fold(st: StrictArrayOfRefsState, r: RandomState): Int = {
    st.foldSparse(r)
  }

  @Benchmark
  @Group("lazy_nonflat")
  def lazy_nonflat_swap(st: SparseArrayOfRefsState, r: RandomState): String = {
    st.swapAndGet(r)
  }

  @Benchmark
  @Group("lazy_nonflat")
  def lazy_nonflat_fold(st: SparseArrayOfRefsState, r: RandomState): Int = {
    st.foldSparse(r)
  }
}

object RefArrayBench {

  private[this] final val INIT = "foobar"

  @State(Scope.Benchmark)
  abstract class BaseState {

    protected final val emcas: Mcas =
      Mcas.Emcas

    private[this] var _arr: Ref.Array[String] =
      null

    protected def mkArr(size: Int): Ref.Array[String]

    final def arr: Ref.Array[String] =
      this._arr

    @Param(Array("8", "32", "512", "8192"))
    var size: Int =
      8

    @Setup
    def setup(): Unit = {
      val a = this.mkArr(this.size)
      (0 until this.size).foreach { idx =>
        a.unsafeGet(idx).set.provide(
          ThreadLocalRandom.current().nextInt().toString
        ).unsafePerform(null, this.emcas)
      }
      this._arr = a
    }

    final def foldSparse(r: RandomState): Int = {
      this.foldSparseAxn(r).unsafePerform(null, this.emcas)
    }

    final def foldSparseAxn(r: RandomState): Axn[Int] = {
      val arr = this.arr
      val len = arr.length
      val incr = 16
      var acc = Axn.pure(0)
      var idx = r.nextIntBounded(4)
      while (idx < len) {
        val hs = arr.unsafeGet(idx).get.map(_.##)
        acc = acc.flatMapF { acc => hs.map(_ ^ acc) }
        idx += incr
      }
      acc
    }

    final def swapAndGet(r: RandomState): String = {
      val len = this.size
      swapAndGetAxn(r.nextIntBounded(len), r.nextIntBounded(len), r.nextIntBounded(len)).unsafePerform(null, this.emcas)
    }

    final def swapAndGetAxn(idx1: Int, idx2: Int, idx3: Int): Axn[String] = {
      val arr = this.arr
      Ref.swap(arr.unsafeGet(idx1), arr.unsafeGet(idx2)) *> arr.unsafeGet(idx3).get
    }
  }

  @State(Scope.Benchmark)
  class StrictArrayState extends BaseState {
    protected final override def mkArr(size: Int): Ref.Array[String] = {
      Ref.unsafeStrictArray(this.size, INIT)
    }
  }

  @State(Scope.Benchmark)
  class SparseArrayState extends BaseState {
    protected final override def mkArr(size: Int): Ref.Array[String] = {
      Ref.unsafeLazyArray(this.size, INIT)
    }
  }

  @State(Scope.Benchmark)
  class StrictArrayOfRefsState extends BaseState {
    protected final override def mkArr(size: Int): Ref.Array[String] = {
      Ref.arrayOfRefs(this.size, INIT).unsafePerform(null, this.emcas)
    }
  }

  @State(Scope.Benchmark)
  class SparseArrayOfRefsState extends BaseState {
    protected final override def mkArr(size: Int): Ref.Array[String] = {
      Ref.lazyArrayOfRefs(this.size, INIT).unsafePerform(null, this.emcas)
    }
  }
}
