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
 * A disposable side-effect that creates a new [ViewModelStore] and [ViewModelStoreOwner],
 * scoping view models to a local store and ensuring the store is cleared when the it leaves the composition.
 *
 * @param key The key used to identify the store. The scope of the store will be decided by this key .
 * @param content The content of the composable.
 */
@Composable
public fun ViewModelStoreScope(
  key: Any,
  content: @Composable () -> Unit,
) {
  // Restart composition on every new instance of the factory
  key(key) {
    /** scope view models to a local store and reset the store with the given [key] */
    val viewModelStore = remember { ViewModelStore() }
    val viewModelStoreOwner = remember(viewModelStore) {
      object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = viewModelStore
      }
    }

    // Ensure the store is cleared when the composable is disposed
    DisposableEffect(Unit) {
      onDispose {
        viewModelStore.clear()
      }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides viewModelStoreOwner) {
      content.invoke()
    }
  }
}
