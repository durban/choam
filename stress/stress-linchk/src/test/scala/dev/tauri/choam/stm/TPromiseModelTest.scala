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
 *   suspend fun read(): String = ...
 *   suspend fun complete1(): Boolean = ...
 *   suspend fun complete2(): Boolean = ...
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
  d1 = Array("\u0000\u001a\n\u0002\u0018\u0002\n\u0002\u0010\u0000\n\u0002\b\u0003\n\u0002\u0010\u000e\n\u0002\b\u0002\n\u0002\u0010\u000b\n\u0000\u0018\u00002\u00020\u0001B\u0007\u00a2\u0006\u0004\b\u0002\u0010\u0003J\u000e\u0010\u0004\u001a\u00020\u0005H\u0086@\u00a2\u0006\u0002\u0010\u0006J\u000e\u0010\u0007\u001a\u00020\bH\u0086@\u00a2\u0006\u0002\u0010\u0006J\u000e\u0010\t\u001a\u00020\bH\u0086@\u00a2\u0006\u0002\u0010\u0006"),
  d2 = Array("Ldev/tauri/choam/stm/TPromiseModelTestState;", "", "<init>", "()V", "read", "", "(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;", "complete1", "", "complete2"),
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
  def read(@NotNull $completion: Continuation[_ >: String]): AnyRef = {
    p.get.commit.apply($completion)
  }

  @Nullable
  @Operation(cancellableOnSuspension = false)
  def complete1(@NotNull $completion: Continuation[_ >: Boolean]): AnyRef = {
    p.complete("result1").commit.apply($completion)
  }

  @Nullable
  @Operation(cancellableOnSuspension = false)
  def complete2(@NotNull $completion: Continuation[_ >: Boolean]): AnyRef = {
    p.complete("result2").commit.apply($completion)
  }
}
