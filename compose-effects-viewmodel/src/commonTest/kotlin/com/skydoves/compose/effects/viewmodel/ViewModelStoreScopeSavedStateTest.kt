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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.SavedStateViewModelFactory
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.savedstate.SavedState
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.savedState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the [SavedStateHandle] contract of [ViewModelStoreScope].
 *
 * The scope owns its own [SavedStateRegistry], so a `SavedStateHandle` obtained inside it works for
 * the lifetime of the scope and is isolated from the host and from sibling scopes. It deliberately
 * does NOT survive process death: nothing the scope holds is written into the host's registry.
 * [scopedSavedStateDoesNotSurviveProcessDeath] pins that limitation so it cannot regress silently
 * into the far worse failure it replaced, where the scope consumed and destroyed the host's entry.
 */
@OptIn(ExperimentalTestApi::class)
class ViewModelStoreScopeSavedStateTest {

  /**
   * Mirrors the shape of the default Compose host owner: a single object that is the view model
   * store owner, the lifecycle owner and the saved state registry owner, with saved state handles
   * enabled on it. [save] is the process-death boundary.
   */
  private class FakeHost(restore: SavedState? = null) :
    ViewModelStoreOwner,
    LifecycleOwner,
    SavedStateRegistryOwner,
    HasDefaultViewModelProviderFactory {
    override val viewModelStore: ViewModelStore = ViewModelStore()
    private val registry = LifecycleRegistry.createUnsafe(this)
    override val lifecycle: Lifecycle get() = registry
    private val controller = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

    // The same factory a real non-Android Compose host exposes.
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory =
      SavedStateViewModelFactory()
    override val defaultViewModelCreationExtras: CreationExtras =
      MutableCreationExtras().also {
        it[SAVED_STATE_REGISTRY_OWNER_KEY] = this
        it[VIEW_MODEL_STORE_OWNER_KEY] = this
      }

    init {
      controller.performAttach()
      controller.performRestore(restore)
      enableSavedStateHandles()
      registry.currentState = Lifecycle.State.RESUMED
    }

    fun save(): SavedState = savedState().also { controller.performSave(it) }
  }

  private class StateViewModel(val handle: SavedStateHandle) : ViewModel()

  private class NoArgViewModel : ViewModel()

  private class RecordingViewModel(private val onClear: () -> Unit) : ViewModel() {
    override fun onCleared() = onClear()
  }

  private fun token(handle: SavedStateHandle): String? = handle.get<String>(TOKEN)

  private companion object {
    const val TOKEN = "token"
  }

  @Test
  fun control_savedStateSurvivesARoundTripOutsideTheScope() = runComposeUiTest {
    // Positive control. If this goes red, every "restored is null" below is unreadable.
    val host = FakeHost()
    var first: StateViewModel? = null
    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides host) {
        first = viewModel { StateViewModel(createSavedStateHandle()) }
      }
    }
    waitForIdle()
    assertNotNull(first).handle[TOKEN] = "HOST"

    val restored = FakeHost(host.save())
    var second: StateViewModel? = null
    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides restored) {
        second = viewModel { StateViewModel(createSavedStateHandle()) }
      }
    }
    waitForIdle()

    assertEquals("HOST", token(assertNotNull(second).handle))
  }

  @Test
  fun savedStateHandleWorksForTheLifetimeOfTheScope() = runComposeUiTest {
    val host = FakeHost()
    var scoped: StateViewModel? = null
    var reread: StateViewModel? = null
    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides host) {
        ViewModelStoreScope(key = "scope-a") {
          scoped = viewModel { StateViewModel(createSavedStateHandle()) }
          // A second lookup in the same scope resolves to the same stored instance.
          reread = viewModel { StateViewModel(createSavedStateHandle()) }
        }
      }
    }
    waitForIdle()

    val viewModel = assertNotNull(scoped)
    assertSame(viewModel, assertNotNull(reread))

    // The handle is usable and reads back through the registry the scope installed, which is what
    // main could not do at all: createSavedStateHandle() threw for want of a registry owner.
    viewModel.handle[TOKEN] = "SCOPED"
    assertEquals("SCOPED", token(viewModel.handle))
    assertEquals(setOf(TOKEN), viewModel.handle.keys())
  }

  @Test
  fun siblingScopesDoNotClobberEachOther() = runComposeUiTest {
    val host = FakeHost()
    var a: StateViewModel? = null
    var b: StateViewModel? = null
    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides host) {
        ViewModelStoreScope(key = "scope-a") {
          a = viewModel { StateViewModel(createSavedStateHandle()) }
        }
        ViewModelStoreScope(key = "scope-b") {
          b = viewModel { StateViewModel(createSavedStateHandle()) }
        }
      }
    }
    waitForIdle()

    assertNotNull(a).handle[TOKEN] = "AAA"
    assertNotNull(b).handle[TOKEN] = "BBB"
    assertEquals("AAA", token(assertNotNull(a).handle))
    assertEquals("BBB", token(assertNotNull(b).handle))

    // Each sibling owns its own registry and store, so the host round trip carries neither, and
    // neither sibling can read the other's value.
    val restored = FakeHost(host.save())
    var a2: StateViewModel? = null
    var b2: StateViewModel? = null
    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides restored) {
        ViewModelStoreScope(key = "scope-a") {
          a2 = viewModel { StateViewModel(createSavedStateHandle()) }
        }
        ViewModelStoreScope(key = "scope-b") {
          b2 = viewModel { StateViewModel(createSavedStateHandle()) }
        }
      }
    }
    waitForIdle()

    assertNull(token(assertNotNull(a2).handle))
    assertNull(token(assertNotNull(b2).handle))
  }

  @Test
  fun hostKeepsItsOwnSavedStateWhenAScopedViewModelIsComposedFirst() = runComposeUiTest {
    val host = FakeHost()
    var scoped: StateViewModel? = null
    var hosted: StateViewModel? = null
    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides host) {
        // Composed BEFORE the host's own view model of the same class, which is what used to let
        // the scope consume and delete the host's restored entry.
        ViewModelStoreScope(key = "scope-a") {
          scoped = viewModel { StateViewModel(createSavedStateHandle()) }
        }
        hosted = viewModel { StateViewModel(createSavedStateHandle()) }
      }
    }
    waitForIdle()

    assertNotNull(scoped).handle[TOKEN] = "SCOPED"
    assertNotNull(hosted).handle[TOKEN] = "HOST"

    val restored = FakeHost(host.save())
    var hosted2: StateViewModel? = null
    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides restored) {
        ViewModelStoreScope(key = "scope-a") {
          viewModel { StateViewModel(createSavedStateHandle()) }
        }
        hosted2 = viewModel { StateViewModel(createSavedStateHandle()) }
      }
    }
    waitForIdle()

    assertEquals("HOST", token(assertNotNull(hosted2).handle))
  }

  @Test
  fun scopedSavedStateDoesNotSurviveProcessDeath() = runComposeUiTest {
    val host = FakeHost()
    var scoped: StateViewModel? = null
    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides host) {
        ViewModelStoreScope(key = "scope-a") {
          scoped = viewModel { StateViewModel(createSavedStateHandle()) }
        }
      }
    }
    waitForIdle()
    assertNotNull(scoped).handle[TOKEN] = "SCOPED"

    val restored = FakeHost(host.save())
    var scoped2: StateViewModel? = null
    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides restored) {
        ViewModelStoreScope(key = "scope-a") {
          scoped2 = viewModel { StateViewModel(createSavedStateHandle()) }
        }
      }
    }
    waitForIdle()

    // Documented limitation, pinned on purpose: the scope's registry is never written into the
    // host's, so a scoped SavedStateHandle starts empty after process death.
    assertNull(token(assertNotNull(scoped2).handle))
  }

  @Test
  fun leavingTheScopeDestroysItsLifecycleBeforeClearingTheStore() = runComposeUiTest {
    val host = FakeHost()
    val events = mutableListOf<String>()
    val visible = mutableStateOf(true)
    var observed: Lifecycle? = null

    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides host) {
        if (visible.value) {
          ViewModelStoreScope(key = "scope-a") {
            observed = assertIs<LifecycleOwner>(
              assertNotNull(LocalViewModelStoreOwner.current),
            ).lifecycle
            // onCleared() runs from ViewModelStore.clear(), so where this lands in `events`
            // orders the store clear against the lifecycle events.
            viewModel { RecordingViewModel { events += "STORE_CLEARED" } }
          }
        }
      }
    }
    waitForIdle()

    // Observed from outside the scope's content: an observer registered inside it is removed on
    // the same disposal pass and never sees the teardown at all.
    val lifecycle = assertNotNull(observed)
    lifecycle.addObserver(LifecycleEventObserver { _, event -> events += event.name })

    // Positive control for the assertion below: the scope is RESUMED, so attaching replays the
    // three upward events. An empty list here would mean no lifecycle was ever driven.
    assertEquals(listOf("ON_CREATE", "ON_START", "ON_RESUME"), events.toList())

    visible.value = false
    waitForIdle()

    assertEquals(Lifecycle.State.DESTROYED, lifecycle.currentState)
    assertEquals(
      listOf(
        "ON_CREATE",
        "ON_START",
        "ON_RESUME",
        "ON_PAUSE",
        "ON_STOP",
        "ON_DESTROY",
        "STORE_CLEARED",
      ),
      events.toList(),
    )
  }

  @Test
  fun factorylessLookupInsideTheScopeBehavesLikeTheHost() = runComposeUiTest {
    // Whatever the host's default factory does with a no-arg view model, the scope must do the
    // same. On non-Android that is a throw out of the SavedStateViewModelFactory stub. Before the
    // scope delegated anything it routed to the reflective default factory instead and quietly
    // succeeded here, which made the scope behave unlike its own host.
    val host = FakeHost()
    var hostOwner: ViewModelStoreOwner? = null
    var scopedOwner: ViewModelStoreOwner? = null
    setContent {
      CompositionLocalProvider(LocalViewModelStoreOwner provides host) {
        hostOwner = LocalViewModelStoreOwner.current
        ViewModelStoreScope(key = "scope-a") {
          scopedOwner = LocalViewModelStoreOwner.current
        }
      }
    }
    waitForIdle()

    // Replays what viewModel() resolves for each owner, outside composition so the outcome can be
    // compared instead of thrown.
    val fromHost = runCatching { noArgViewModelVia(assertNotNull(hostOwner)) }
    val fromScope = runCatching { noArgViewModelVia(assertNotNull(scopedOwner)) }

    assertEquals(fromHost.isSuccess, fromScope.isSuccess)
    assertEquals(
      fromHost.exceptionOrNull()?.let { it::class },
      fromScope.exceptionOrNull()?.let { it::class },
    )
  }

  /** The resolution `androidx.lifecycle.viewmodel.compose.viewModel()` performs internally. */
  private fun noArgViewModelVia(owner: ViewModelStoreOwner): ViewModel {
    val withDefaults = owner as? HasDefaultViewModelProviderFactory
    val provider = if (withDefaults != null) {
      ViewModelProvider.create(
        owner.viewModelStore,
        withDefaults.defaultViewModelProviderFactory,
        withDefaults.defaultViewModelCreationExtras,
      )
    } else {
      ViewModelProvider.create(owner)
    }
    return provider[NoArgViewModel::class]
  }
}
