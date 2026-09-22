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
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.enableSavedStateHandles
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * A disposable side-effect that creates a new [ViewModelStore] and [ViewModelStoreOwner], scoping
 * view models to a local store and clearing the store when this scope leaves the composition or the
 * [key] changes.
 *
 * If the enclosing [LocalViewModelStoreOwner] implements [HasDefaultViewModelProviderFactory], the
 * scoped owner delegates that factory, so a lookup with no explicit factory resolves exactly as it
 * would outside the scope. That is what makes `hiltViewModel()` usable here: without it the scope
 * falls back to the reflective default factory and cannot construct a view model that takes
 * constructor arguments.
 *
 * The scope then owns its own [SavedStateRegistry] and [Lifecycle], and points both
 * [SAVED_STATE_REGISTRY_OWNER_KEY] and [VIEW_MODEL_STORE_OWNER_KEY] at itself. Nothing it does
 * reaches the host's registry, so sibling scopes cannot collide and the host keeps its own saved
 * state. This is required, not optional: `HiltViewModelFactory` calls `createSavedStateHandle()`
 * for every `@HiltViewModel` whether or not that view model injects a [SavedStateHandle], so a
 * factory delegated without a working registry only moves the failure.
 *
 * The cost is stated plainly: a [SavedStateHandle] obtained inside the scope works for the lifetime
 * of the scope but does NOT survive process death, because the scope's registry is never written
 * into the host's. That is the same lifetime as the scoped [ViewModelStore] itself.
 *
 * Three behaviors worth knowing:
 * - The default Compose host off Android exposes `SavedStateViewModelFactory`, whose non-Android
 *   actual is an unimplemented stub, so under that host a lookup with no explicit factory throws
 *   inside the scope exactly as it does outside it. Pass a factory there, for example
 *   `viewModel { MyViewModel() }`. This is a property of that host and not of the platform: a host
 *   supplying its own [HasDefaultViewModelProviderFactory] works off Android too. It is also a
 *   change: before the scope delegated anything it fell through to the reflective default factory
 *   and a no-argument view model resolved on desktop where the host itself would have thrown.
 * - [LocalViewModelStoreOwner] inside the scope is the scoped owner, but `LocalSavedStateRegistryOwner`
 *   and `LocalLifecycleOwner` are still the host's. Read the registry owner off the scoped owner, or
 *   out of [SAVED_STATE_REGISTRY_OWNER_KEY], rather than pairing the two composition locals.
 * - If the enclosing owner has no default factory, the scoped owner is a plain [ViewModelStoreOwner]
 *   with no registry and no lifecycle, unchanged from before this delegation existed.
 *
 * @param key Identifies the store; the store is reset whenever this key changes.
 * @param content The content of the composable.
 */
@Composable
public fun ViewModelStoreScope(key: Any, content: @Composable () -> Unit) {
  // Narrowed before it reaches the remember key: an enclosing owner with nothing to delegate always
  // resolves to null, so the plain-owner path is unaffected by parent identity churn.
  val parent = LocalViewModelStoreOwner.current as? HasDefaultViewModelProviderFactory

  // Restart composition on every new key so the store is reset.
  key(key) {
    val store = remember { ViewModelStore() }
    val owner = remember(store, parent) {
      if (parent != null) {
        ScopedViewModelStoreOwner(store, parent)
      } else {
        object : ViewModelStoreOwner {
          override val viewModelStore: ViewModelStore = store
        }
      }
    }
    val currentOwner = rememberUpdatedState(owner)

    // Ensure the store is cleared when this scope leaves the composition. Keyed on Unit so that a
    // new parent replaces the owner without destroying the view models living in the store.
    DisposableEffect(Unit) {
      onDispose {
        (currentOwner.value as? ScopedViewModelStoreOwner)?.destroy()
        store.clear()
      }
    }

    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
      content.invoke()
    }
  }
}

/**
 * A scoped owner that borrows [parent]'s default factory but keeps its own saved-state registry and
 * lifecycle, so view models created here neither read from nor write to the host's registry.
 *
 * Comparable to `androidx.lifecycle.viewmodel.ViewModelStoreOwnerFactory` in lifecycle 2.11, except
 * that this owner always registers its own provider. That version delegates the host's registry by
 * default and skips [enableSavedStateHandles] when the host already registered one, which leaves
 * the provider bound to the host's store while the view model lives in the child's.
 */
private class ScopedViewModelStoreOwner(
  override val viewModelStore: ViewModelStore,
  private val parent: HasDefaultViewModelProviderFactory,
) : ViewModelStoreOwner,
  HasDefaultViewModelProviderFactory,
  LifecycleOwner,
  SavedStateRegistryOwner {

  private val lifecycleRegistry = LifecycleRegistry(this)
  private val controller = SavedStateRegistryController.create(this)

  override val lifecycle: Lifecycle get() = lifecycleRegistry

  override val savedStateRegistry: SavedStateRegistry get() = controller.savedStateRegistry

  override val defaultViewModelProviderFactory: ViewModelProvider.Factory
    get() = parent.defaultViewModelProviderFactory

  // Recomputed on every read: the copy leaves the parent's extras unmutated, and both owner keys
  // must name this owner so the saved-state provider and the view model land in the same store.
  override val defaultViewModelCreationExtras: CreationExtras
    get() = MutableCreationExtras(parent.defaultViewModelCreationExtras).also { extras ->
      extras[SAVED_STATE_REGISTRY_OWNER_KEY] = this
      extras[VIEW_MODEL_STORE_OWNER_KEY] = this
    }

  init {
    // The load-bearing constraint is that all of this runs before the state leaves INITIALIZED:
    // performAttach requires exactly INITIALIZED, performRestore is rejected at STARTED or above,
    // and the provider enableSavedStateHandles installs only restores once ON_CREATE is
    // dispatched. performRestore and enableSavedStateHandles commute with each other.
    controller.performAttach()
    controller.performRestore(null)
    enableSavedStateHandles()
    lifecycleRegistry.currentState = Lifecycle.State.RESUMED
  }

  fun destroy() {
    lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
  }
}
