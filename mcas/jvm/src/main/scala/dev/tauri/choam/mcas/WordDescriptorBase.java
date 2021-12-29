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

package dev.tauri.choam.mcas;

import java.lang.ref.WeakReference;
import java.lang.invoke.VarHandle;
import java.lang.invoke.MethodHandles;

// TODO: remove this
final class McasMarker {}

abstract class WordDescriptorBase extends WeakReference<Object> {

  private static final VarHandle STRONG;
  private static final VarHandle PREDECESSOR;

  static {
    try {
      MethodHandles.Lookup l = MethodHandles.lookup();
      STRONG = l.findVarHandle(WordDescriptorBase.class, "_strong", Object.class);
      PREDECESSOR = l.findVarHandle(WordDescriptorBase.class, "_predecessor", WordDescriptorBase.class);
    } catch (ReflectiveOperationException ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  @SuppressWarnings("unused")
  private volatile Object _strong;

  private volatile WordDescriptorBase _predecessor;

  WordDescriptorBase() {
    this(new McasMarker());
  }

  private WordDescriptorBase(Object marker) {
    super(marker);
    STRONG.set(this, marker);
    // plain write is enough, as descriptors
    // are only exchanged through `Ref`s
  }

  protected final Object getStrongRefPlain() {
    return STRONG.get(this);
  }

  protected final void setStrongRefOpaque(Object to ) {
    STRONG.setOpaque(this, to);
  }

  protected final WordDescriptorBase getPredecessorVolatile() {
    return this._predecessor;
  }

  protected final boolean casPredecessorVolatile(WordDescriptorBase ov, WordDescriptorBase nv) {
    return PREDECESSOR.compareAndSet(this, ov, nv);
  }
}
