/*
 * Designed and developed by 2025 skydoves (Jaewoong Eum)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.skydoves.compose.effects.viewmodel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * A disposable side-effect that creates a new [ViewModelStore] and [ViewModelStoreOwner], scoping
 * view models to a local store and clearing the store when this scope leaves the composition or the
 * [key] changes.
 *
 * The scoped owner only provides a [ViewModelStore]. On Kotlin/Native (iOS, macOS) the reflective
 * default factory is unavailable, so obtain view models with an explicit factory, for example
 * `viewModel { MyViewModel() }`.
 *
 * @param key Identifies the store; the store is reset whenever this key changes.
 * @param content The content of the composable.
 */
@Composable
public fun ViewModelStoreScope(key: Any, content: @Composable () -> Unit) {
  // Restart composition on every new key so the store is reset.
  key(key) {
    val store = remember { ViewModelStore() }
    val owner = remember(store) {
      object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore = store
      }
    }

    // Ensure the store is cleared when this scope leaves the composition.
    DisposableEffect(Unit) {
      onDispose { store.clear() }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
      content.invoke()
    }
  }
}
