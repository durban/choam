/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2020 Daniel Urban and contributors listed in NOTICE.txt
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
package kcas

import org.scalatest.{ FlatSpec, Matchers }
import org.scalactic.TypeCheckedTripleEquals

@deprecated("so that we can test deprecated methods", since = "we need it")
class MCASSpec extends FlatSpec with Matchers with TypeCheckedTripleEquals {

  // We need this here to access internals:
  import scala.language.reflectiveCalls

  type Desc = {
    def incr(): Unit
    def decr(): Unit
  }

  def headOf(d: Desc): Entry = {
    val f = d.getClass.getDeclaredField("head")
    f.setAccessible(true)
    f.get(d).asInstanceOf[Entry]
  }

  def kOf(d: Desc): Int = {
    val f = try {
      d.getClass.getDeclaredField("k")
    } catch {
      case _: NoSuchFieldException =>
        d.getClass.getDeclaredField("dev$tauri$choam$kcas$MCAS$MCASDesc$$k")
    }
    f.setAccessible(true)
    f.getInt(d)
  }

  type Entry = {
    def ref: Ref[_]
    def next: Any
  }

  def getTlSt(): Any = {
    val cls = Class.forName("dev.tauri.choam.kcas.MCAS$TlSt$")
    val mod = cls.getDeclaredField("MODULE$")
    mod.setAccessible(true)
    val inst = cls.getDeclaredField("inst")
    inst.setAccessible(true)
    val tl = inst.get(mod.get(null)).asInstanceOf[ThreadLocal[_]]
    tl.get()
  }

  def getTlStK(): Int = {
    val obj = getTlSt()
    val kf = obj.getClass().getDeclaredField("maxK")
    kf.setAccessible(true)
    kf.getInt(obj)
  }

  def getTlStAllocCount(): Long = {
    val obj = getTlSt()
    val acf = obj.getClass().getDeclaredField("numAllocs")
    acf.setAccessible(true)
    acf.getLong(obj)
  }

  def getTlStNumFree(): Long = {
    val obj = getTlSt()
    val ef = obj.getClass().getDeclaredField("numFreeEntries")
    ef.setAccessible(true)
    val df = obj.getClass().getDeclaredField("numFreeDescriptors")
    df.setAccessible(true)
    ef.getInt(obj).toLong + df.getInt(obj).toLong
  }

  "MCAS" should "sort the entries before performing the CAS" in {
    val N = 20
    val refs = List.fill(N)(Ref.mk[String]("s"))
    val desc = MCAS.start().asInstanceOf[MCAS.Desc with Desc]
    desc.incr() // to avoid releasing it after `perform`
    refs.foldLeft(0) { (k, ref) =>
      kOf(desc) should === (k)
      desc.withCAS(ref, "s", "t")
      k + 1
    }
    kOf(desc) should === (N)
    assert(desc.tryPerform())
    kOf(desc) should === (N)

    @tailrec
    def extract(h: Entry, acc: List[Ref[String]]): List[Ref[String]] = {
      if (h eq null) {
        acc
      } else {
        extract(h.next.asInstanceOf[Entry], h.ref.asInstanceOf[Ref[String]] :: acc)
      }
    }

    val sortedRefs = extract(headOf(desc), Nil)
    sortedRefs should === (refs.sortBy(_.bigId).reverse)

    desc.decr()
    kOf(desc) should === (0)
  }

  it should "save the size of the performed CAS to thread-local state" in {
    val N = 17 * React.maxStackDepth
    val refs = List.fill(N)(Ref.mk[String]("s"))
    val desc = MCAS.start().asInstanceOf[MCAS.Desc with Desc]
    for (ref <- refs) {
      desc.withCAS(ref, "s", "t")
    }
    assert(desc.tryPerform())
    getTlStK() should === (N)
    kOf(desc) should === (0)
  }

  it should "calculate a correct K when saving/loading snapshots" in {
    val N = 5
    val refs = List.fill(N)(Ref.mk[String]("s"))
    val desc = MCAS.start().asInstanceOf[MCAS.Desc with Desc]
    kOf(desc) should === (0)
    desc.withCAS(refs(0), "s", "t")
    desc.withCAS(refs(1), "s", "t")
    desc.withCAS(refs(2), "s", "t")
    kOf(desc) should === (3)
    val snap = desc.snapshot()
    kOf(desc) should === (3)
    desc.withCAS(refs(3), "s", "t")
    desc.withCAS(refs(4), "s", "t")
    kOf(desc) should === (5)
    assert(desc.tryPerform())
    kOf(desc) should === (0)
    val desc2 = snap.load().asInstanceOf[MCAS.Desc with Desc]
    kOf(desc2) should === (3)
    assert(!desc2.tryPerform())
    kOf(desc2) should === (0)
  }

  it should "not leak descriptors/entries" in {
    val init: React[Unit, Unit] = Ref.mk("x").modify(_ + "x").discard
    val r1 = Ref.mk("foo")
    val r2 = Ref.mk("bar")
    val r3 = Ref.mk("baz")
    val r4 = Ref.mk("x")
    val r5 = Ref.mk("y")
    val r6 = Ref.mk("z")
    val r = r3.cas("baz", "x") + (React.swap(r1, r2) >>> (r6.cas("z", "zz") + React.swap(r4, r5)))

    @volatile var err: Throwable = null
    val h = new Thread.UncaughtExceptionHandler {
      override def uncaughtException(t: Thread, ex: Throwable): Unit = {
        err = ex
      }
    }

    var deltaFree1: Long = 0L
    var deltaAlloc1: Long = 0L
    val t1 = new Thread {
      this.setUncaughtExceptionHandler(h)
      override def run(): Unit = {
        init.unsafeRun(MCAS)
        val free = getTlStNumFree()
        val alloc = getTlStAllocCount()
        r.unsafeRun(MCAS)
        deltaFree1 = getTlStNumFree() - free
        deltaAlloc1 = getTlStAllocCount() - alloc
      }
    }
    t1.start()
    t1.join()
    if (err ne null) throw err
    r1.unsafeTryRead() should === ("foo")
    r2.unsafeTryRead() should === ("bar")
    r3.unsafeTryRead() should === ("x")
    r4.unsafeTryRead() should === ("x")
    r5.unsafeTryRead() should === ("y")
    r6.unsafeTryRead() should === ("z")
    deltaAlloc1 should === (deltaFree1)
    this.info(s"${deltaAlloc1} new allocations")

    var deltaFree2: Long = 0L
    var deltaAlloc2: Long = 0L
    val t2 = new Thread {
      this.setUncaughtExceptionHandler(h)
      override def run(): Unit = {
        init.unsafeRun(MCAS)
        val free = getTlStNumFree()
        val alloc = getTlStAllocCount()
        r.unsafeRun(MCAS)
        deltaFree2 = getTlStNumFree() - free
        deltaAlloc2 = getTlStAllocCount() - alloc
      }
    }
    t2.start()
    t2.join()
    if (err ne null) throw err
    r1.unsafeTryRead() should === ("bar")
    r2.unsafeTryRead() should === ("foo")
    r3.unsafeTryRead() should === ("x")
    r4.unsafeTryRead() should === ("x")
    r5.unsafeTryRead() should === ("y")
    r6.unsafeTryRead() should === ("zz")
    deltaAlloc2 should === (deltaFree2)
    this.info(s"${deltaAlloc2} new allocations")

    var deltaFree3: Long = 0L
    var deltaAlloc3: Long = 0L
    val t3 = new Thread {
      this.setUncaughtExceptionHandler(h)
      override def run(): Unit = {
        init.unsafeRun(MCAS)
        val free = getTlStNumFree()
        val alloc = getTlStAllocCount()
        r.unsafeRun(MCAS)
        deltaFree3 = getTlStNumFree() - free
        deltaAlloc3 = getTlStAllocCount() - alloc
      }
    }
    t3.start()
    t3.join()
    if (err ne null) throw err
    r1.unsafeTryRead() should === ("foo")
    r2.unsafeTryRead() should === ("bar")
    r3.unsafeTryRead() should === ("x")
    r4.unsafeTryRead() should === ("y")
    r5.unsafeTryRead() should === ("x")
    r6.unsafeTryRead() should === ("zz")
    deltaAlloc3 should === (deltaFree3)
    this.info(s"${deltaAlloc3} new allocations")
  }
}
