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
package unsafe

import scala.util.control.ControlThrowable

private[choam] final class RetryException private (private[choam] val sus: CanSuspendInF) extends ControlThrowable {

  final override def fillInStackTrace(): Throwable =
    this

  final override def initCause(cause: Throwable): Throwable =
    throw new IllegalStateException
}

private[choam] object RetryException {

  private[choam] val instance: RetryException =
    new RetryException(null)

  private[choam] final def fromSuspend(sus: CanSuspendInF): RetryException = {
    require(sus ne null)
    new RetryException(sus)
  }
}
