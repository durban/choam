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
package unsafe

import stm.{ TRef, Txn }

object UnsafeStm { // TODO: better name

  // TODO: separate implicit for these, e.g., `InTxn`?

  final def newTRef[A](initial: A)(implicit ir: InRxn): TRef[A] =
    TRef.unsafe(initial)(ir.currentContext())

  final def updateTRef[A](ref: TRef[A])(f: A => A)(implicit ir: InRxn): Unit = {
    ir.updateRef(ref.refImpl.loc, f)
  }

  final def embedTxn[A](txn: Txn[A])(implicit ir: InRxn): A = {
    ir.embedRxn(txn.impl)
  }

  private[choam] final def retryWhenChanged()(implicit ir: InRxn): Nothing = {
    throw RetryException.permanentFailure
  }
}
