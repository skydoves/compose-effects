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

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Verifies that [ViewModelStoreScope] delegates the parent owner's default
 * [ViewModelProvider.Factory] and [CreationExtras] into the scope.
 *
 * Without that delegation the scoped owner is a bare [ViewModelStoreOwner], so `viewModel()` and
 * anything built on it (`hiltViewModel()`, `koinViewModel()`, ...) silently fall back to the
 * reflective default factory and fail for any view model with constructor arguments.
 */
@OptIn(ExperimentalTestApi::class)
class ViewModelStoreScopeDelegationTest {

  private companion object {
    /** A custom extras key that only the fake parent puts into its default extras. */
    val CUSTOM_KEY = object : CreationExtras.Key<String> {}
  }

  /**
   * A view model with a required constructor argument, so the reflective default factory cannot
   * build it. This is the same shape as a Hilt/Koin view model with injected dependencies.
   */
  private class SentinelViewModel(
    val origin: String,
    val storeOwner: ViewModelStoreOwner?,
    val custom: String?,
  ) : ViewModel()

  /** Records the extras it was invoked with and stamps [origin] on the created view model. */
  private class RecordingFactory(private val origin: String) : ViewModelProvider.Factory {
    var invocations: Int = 0
      private set

    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T {
      invocations++
      @Suppress("UNCHECKED_CAST")
      return SentinelViewModel(
        origin = origin,
        storeOwner = extras[VIEW_MODEL_STORE_OWNER_KEY],
        custom = extras[CUSTOM_KEY],
      ) as T
    }
  }

  private class DefaultFactoryParent(
    val factory: ViewModelProvider.Factory,
    private val extras: CreationExtras,
  ) : ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory {
    override val viewModelStore: ViewModelStore = ViewModelStore()
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory get() = factory
    override val defaultViewModelCreationExtras: CreationExtras get() = extras
  }

  /** A parent that deliberately does NOT implement [HasDefaultViewModelProviderFactory]. */
  private class PlainParent : ViewModelStoreOwner {
    override val viewModelStore: ViewModelStore = ViewModelStore()
  }

  private class CountingViewModel : ViewModel() {
    var cleared: Boolean = false
      private set

    override fun onCleared() {
      cleared = true
    }
  }

  private fun parentWithCustomExtras(origin: String): DefaultFactoryParent {
    val extras = MutableCreationExtras().apply { set(CUSTOM_KEY, "from-parent") }
    return DefaultFactoryParent(factory = RecordingFactory(origin), extras = extras)
  }

  @Test
  fun usesParentDefaultFactoryForViewModelsCreatedInsideTheScope() = runComposeUiTest {
    val parent = parentWithCustomExtras(origin = "parent-default-factory")
    var scoped: SentinelViewModel? = null

    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides parent) {
        ViewModelStoreScope(key = "delegation") {
          // No explicit factory: this is exactly what hiltViewModel()/koinViewModel() do.
          scoped = viewModel<SentinelViewModel>()
        }
      }
    }
    waitForIdle()

    val viewModel = assertNotNull(scoped)
    assertEquals("parent-default-factory", viewModel.origin)
    assertEquals(1, (parent.factory as RecordingFactory).invocations)
  }

  @Test
  fun viewModelStoreOwnerKeyPointsAtTheScopedOwnerNotTheParent() = runComposeUiTest {
    val parent = parentWithCustomExtras(origin = "parent-default-factory")
    var scoped: SentinelViewModel? = null
    var scopedOwner: ViewModelStoreOwner? = null

    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides parent) {
        ViewModelStoreScope(key = "delegation") {
          scopedOwner = LocalViewModelStoreOwner.current
          scoped = viewModel<SentinelViewModel>()
        }
      }
    }
    waitForIdle()

    val viewModel = assertNotNull(scoped)
    val owner = assertNotNull(scopedOwner)
    // The view model lives in the child store, so the key must resolve to the child owner.
    assertSame(owner, viewModel.storeOwner)
    assertNotSame(parent, viewModel.storeOwner)
    assertNotSame(parent.viewModelStore, owner.viewModelStore)
  }

  @Test
  fun customParentExtrasKeysRemainReadableInsideTheScope() = runComposeUiTest {
    val parent = parentWithCustomExtras(origin = "parent-default-factory")
    var scoped: SentinelViewModel? = null

    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides parent) {
        ViewModelStoreScope(key = "delegation") {
          scoped = viewModel<SentinelViewModel>()
        }
      }
    }
    waitForIdle()

    assertEquals("from-parent", assertNotNull(scoped).custom)
  }

  @Test
  fun delegatingDoesNotMutateTheParentExtras() = runComposeUiTest {
    val parent = parentWithCustomExtras(origin = "parent-default-factory")

    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides parent) {
        ViewModelStoreScope(key = "delegation") {
          viewModel<SentinelViewModel>()
        }
      }
    }
    waitForIdle()

    // The child must build its extras from a copy; the parent's own extras stay untouched.
    assertEquals(null, parent.defaultViewModelCreationExtras[VIEW_MODEL_STORE_OWNER_KEY])
  }

  @Test
  fun parentWithoutDefaultFactoryKeepsTheBareScopedOwner() = runComposeUiTest {
    val parent = PlainParent()
    var scopedOwner: ViewModelStoreOwner? = null
    var scoped: CountingViewModel? = null

    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides parent) {
        ViewModelStoreScope(key = "no-delegation") {
          scopedOwner = LocalViewModelStoreOwner.current
          scoped = viewModel { CountingViewModel() }
        }
      }
    }
    waitForIdle()

    val owner = assertNotNull(scopedOwner)
    // Nothing to delegate, so the scope must not synthesize a default factory of its own.
    assertFalse(owner is HasDefaultViewModelProviderFactory)
    assertNotSame(parent, owner)
    assertNotSame(parent.viewModelStore, owner.viewModelStore)
    assertFalse(assertNotNull(scoped).cleared)
  }

  @Test
  fun scopedOwnerExposesTheParentDefaultFactoryUnchanged() = runComposeUiTest {
    val parent = parentWithCustomExtras(origin = "parent-default-factory")
    var scopedOwner: ViewModelStoreOwner? = null
    var created: CountingViewModel? = null

    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides parent) {
        ViewModelStoreScope(key = "delegation") {
          scopedOwner = LocalViewModelStoreOwner.current
          created = viewModel { CountingViewModel() }
        }
      }
    }
    waitForIdle()

    val owner = assertIs<HasDefaultViewModelProviderFactory>(assertNotNull(scopedOwner))
    assertSame(parent.defaultViewModelProviderFactory, owner.defaultViewModelProviderFactory)
    // An explicit initializer still wins over the delegated default factory.
    assertNotNull(created)
    assertEquals(0, (parent.factory as RecordingFactory).invocations)
  }
}
