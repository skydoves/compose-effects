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

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Multiplatform behavioral tests for [ViewModelStoreScope], executed on every Compose Multiplatform
 * target. They verify the scoping contract: a view model is scoped to the local store, kept across
 * recompositions, and the store is cleared (so [ViewModel.onCleared] runs) when the key changes or
 * the scope leaves the composition.
 *
 * An explicit initializer factory is used so the test exercises the scoping/clearing logic without
 * relying on the reflective default factory (which is unavailable on Kotlin/Native).
 */
@OptIn(ExperimentalTestApi::class)
class ViewModelStoreScopeTest {

  private class CountingViewModel : ViewModel() {
    var cleared: Boolean = false
      private set

    override fun onCleared() {
      cleared = true
    }
  }

  @Test
  fun keepsScopedViewModelAcrossRecompositionsWhenKeyUnchanged() = runComposeUiTest {
    val tick = mutableIntStateOf(0)
    var current: CountingViewModel? = null

    setContent {
      // Subscribe the root scope to `tick` so we can force recompositions.
      tick.intValue
      ViewModelStoreScope(key = "fixed-key") {
        current = viewModel { CountingViewModel() }
      }
    }
    waitForIdle()
    val first = assertNotNull(current)
    assertFalse(first.cleared)

    repeat(3) {
      tick.intValue++
      waitForIdle()
    }

    // Same scoped instance survives recomposition and is not cleared.
    assertSame(first, current)
    assertFalse(first.cleared)
  }

  @Test
  fun clearsPreviousStoreWhenKeyChanges() = runComposeUiTest {
    val key = mutableIntStateOf(0)
    var current: CountingViewModel? = null

    setContent {
      ViewModelStoreScope(key = key.intValue) {
        current = viewModel { CountingViewModel() }
      }
    }
    waitForIdle()
    val first = assertNotNull(current)
    assertFalse(first.cleared)

    key.intValue = 1
    waitForIdle()
    val second = assertNotNull(current)

    // A new key creates a fresh store/instance and clears the previous store.
    assertNotSame(first, second)
    assertTrue(first.cleared)
    assertFalse(second.cleared)
  }

  @Test
  fun clearsStoreWhenLeavingComposition() = runComposeUiTest {
    val visible = mutableStateOf(true)
    var created: CountingViewModel? = null

    setContent {
      if (visible.value) {
        ViewModelStoreScope(key = "fixed-key") {
          created = viewModel { CountingViewModel() }
        }
      }
    }
    waitForIdle()
    val viewModel = assertNotNull(created)
    assertFalse(viewModel.cleared)

    visible.value = false
    waitForIdle()

    // Leaving the composition disposes the scope and clears its store.
    assertTrue(viewModel.cleared)
  }
}
