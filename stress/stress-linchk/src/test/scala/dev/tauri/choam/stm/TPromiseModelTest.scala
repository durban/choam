/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2016-2026 Daniel Urban and contributors listed in NOTICE.txt
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
package stm

import cats.effect.SyncIO

import org.jetbrains.annotations.{ NotNull, Nullable }
import kotlin.coroutines.Continuation

import org.jetbrains.lincheck.datastructures.Operation

import munit.FunSuite

// import kotlin.metadata.jvm.KotlinClassMetadata
// import kotlin.metadata.{ KmClass, KmFunction, KmType, Attributes }

import KotlinUtils.KtCrt

final class TPromiseModelTest extends FunSuite with RxnLinchkSpec {

  test("Model checking TPromise".tag(SLOW)) {
    val opts = fastModelCheckingOptions()
    opts.check(classOf[TPromiseModelTestState])
  }

  // test("foo".only) {
  //   val ann = classOf[TestState].getAnnotation(classOf[kotlin.Metadata])
  //   val newMeta = KotlinClassMetadata.Companion.transform(ann, { meta =>
  //     val kmClass = meta.asInstanceOf[{ def getKmClass: KmClass }].getKmClass
  //     val func = kmClass.getFunctions().get(0)
  //     func.setName("read")
  //     val stringType = func.getValueParameters().get(0).getType()
  //     kotlin.Unit.INSTANCE
  //   })
  //   println(newMeta)
  // }
}

/*
 * This is the metadata the Kotlin compiler would
 * generate for a class like this:
 *
 * ```
 * class TestState {
 *   suspend fun op0I_1(): Int = 0
 *   suspend fun op0I_2(): Int = 0
 *   suspend fun op1II_1(i: Int): Int = 0
 *   suspend fun op0S_1(): String = ""
 *   suspend fun op0S_2(): String = ""
 *   suspend fun op1SS_1(s: String): String = ""
 * }
 * ```
 *
 * We need to put it here, otherwise Lincheck (using
 * Kotlin reflection) will not realize that our operations
 * are "suspend functions". Also, making `TestState` an
 * inner class messes up somehow the expected metadata(?),
 * so we need it to be top-level.
 */
@kotlin.Metadata(
  mv = Array(2, 1, 0),
  k = 1,
  xi = 48,
  d1 = Array("\u0000\u001c\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\b\n\u0002\b\u0006\n\u0002\u0010\u000e\n\u0002\b\u0004\u0018\u00002\u00020\u0001B\u0007\u00a2\u0006\u0004\b\u0002\u0010\u0003J\u000e\u0010\u0004\u001a\u00020\u0005H\u0086@\u00a2\u0006\u0002\u0010\u0006J\u000e\u0010\u0007\u001a\u00020\u0005H\u0086@\u00a2\u0006\u0002\u0010\u0006J\u0016\u0010\b\u001a\u00020\u00052\u0006\u0010\t\u001a\u00020\u0005H\u0086@\u00a2\u0006\u0002\u0010\nJ\u000e\u0010\u000b\u001a\u00020\fH\u0086@\u00a2\u0006\u0002\u0010\u0006J\u000e\u0010\r\u001a\u00020\fH\u0086@\u00a2\u0006\u0002\u0010\u0006J\u0016\u0010\u000e\u001a\u00020\f2\u0006\u0010\u000f\u001a\u00020\fH\u0086@\u00a2\u0006\u0002\u0010\u0010"),
  d2 = Array("Ldev/tauri/choam/stm/TPromiseModelTestState;", "", "<init>", "()V", "op0I_1", "", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "op0I_2", "op1II_1", "i", "(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;", "op0S_1", "", "op0S_2", "op1SS_1", "s", "(Ljava/lang/String;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;"),
)
private class TPromiseModelTestState {

  private[this] val crt: ChoamRuntime =
    ChoamRuntime.forTesting(RxnLinchkSpec.defaultMcasForTesting)

  private[this] implicit val K: cats.effect.Async[KotlinUtils.KtCrt] =
    KotlinUtils.ceAsyncForKotlinCoroutine

  private[this] implicit val transactive: Transactive[KtCrt] =
    Transactive.fromIn[SyncIO, KtCrt](crt).allocated.unsafeRunSync()._1

  private[this] val p: TPromise[String] =
    KotlinUtils.runCompletelyOrThrow(TPromise[String].commit)

  @Nullable
  @Operation(cancellableOnSuspension = false, blocking = true)
  def op0S_1(@NotNull $completion: Continuation[_ >: String]): AnyRef = {
    p.get.commit.apply($completion)
  }

  @Nullable
  @Operation(cancellableOnSuspension = false)
  def op0I_1(@NotNull $completion: Continuation[_ >: Int]): AnyRef = {
    p.complete("result1").map(if (_) 1 else 0).commit.apply($completion)
  }

  @Nullable
  @Operation(cancellableOnSuspension = false)
  def op0I_2(@NotNull $completion: Continuation[_ >: Int]): AnyRef = {
    p.complete("result2").map(if (_) 1 else 0).commit.apply($completion)
  }
}
