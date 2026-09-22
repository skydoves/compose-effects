<h1 align="center">Compose Effects</h1></br>

<p align="center">
  <a href="https://opensource.org/licenses/Apache-2.0"><img alt="License" src="https://img.shields.io/badge/License-Apache%202.0-blue.svg"/></a>
  <a href="https://android-arsenal.com/api?level=21"><img alt="API" src="https://img.shields.io/badge/API-21%2B-brightgreen.svg?style=flat"/></a>
  <a href="https://github.com/skydoves/compose-effects/actions/workflows/android.yml"><img alt="Build Status" 
  src="https://github.com/skydoves/compose-effects/actions/workflows/android.yml/badge.svg"/></a>
  <a href="https://github.com/skydoves"><img alt="Profile" src="https://skydoves.github.io/badges/skydoves.svg"/></a>
  <a href="https://github.com/doveletter"><img alt="Profile" src="https://skydoves.github.io/badges/dove-letter.svg"/></a>
</p><br>

<p align="center">🧵 Compose Effects enable you to launch efficient side-effects without unnecessary operations.</p>

## Compose Effects

Jetpack Compose provides three primary side-effect handlers: `LaunchedEffect`, `DisposableEffect`, and `SideEffect`. Among them, `LaunchedEffect` is particularly useful for executing side effects whenever a specified key changes. However, it is best suited for coroutine-based tasks, as it creates a new coroutine scope and re-launches the task whenever the key changes, canceling any previously running job.

This behavior can introduce unnecessary overhead by creating redundant coroutine scopes and tasks, even in cases where you simply want to track key changes and launch a non-coroutine based task without re-triggering the effect during recomposition.

Compose Effects offer a straightforward solution to avoid this minor overhead by providing APIs, such as:

```diff
var count by remember { mutableIntStateOf(0) }

 // LaunchedEffect will launch a new coroutine scope regardless the task is related to the coroutines.
 // You can avoid this by using RememberedEffect for executing non-coroutine tasks.
- LaunchedEffect(key1 = count) {
+ RememberedEffect(key1 = count) {
    Log.d(tag, "$count")
}

Button(onClick = { count++ }) {
    Text("Count: $count")
}
```

[![Maven Central](https://img.shields.io/maven-central/v/com.github.skydoves/compose-effects.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22com.github.skydoves%22%20AND%20a:%22flow-operators%22)

### Version Catalog

If you're using Version Catalog, you can configure the dependency by adding it to your `libs.versions.toml` file as follows:

```toml
[versions]
#...
composeEffects = "0.3.0"

[libraries]
#...
compose-effects = { module = "com.github.skydoves:compose-effects", version.ref = "composeEffects" }
```

### Gradle

Add the dependency below to your **module**'s `build.gradle.kts` file:

```gradle
dependencies {
    implementation("com.github.skydoves:compose-effects:$version")
    
    // if you're using Version Catalog
    implementation(libs.compose.effects)
}
```

For Kotlin Multiplatform, add the dependency below to your **module**'s `build.gradle.kts` file:

```gradle
sourceSets {
    val commonMain by getting {
        dependencies {
            implementation("com.github.skydoves:compose-effects:$version")
        }
    }
}
```

### RememberedEffect

`RememberedEffect` is a side-effect API that executes the provided lambda function when it enters the composition and re-executes it whenever key changes.

Unlike `LaunchedEffect`, `RememberedEffect` does not create or launch a new coroutine scope on each key change, making it a more efficient option for remembering the execution of side-effects, if you don't to launch a coroutine task.

```kotlin
var count by remember { mutableIntStateOf(0) }

// Unlike LaunchedEffect, this won't launch a new coroutine scope when the key changes.
RememberedEffect(key1 = count) {
    Log.d(tag, "$count")
}

Button(onClick = { count++ }) {
    Text("Count: $count")
}
```

### ChangedEffect

`ChangedEffect` is a side-effect API that executes the provided lambda function only when the key actually changes. Unlike `RememberedEffect` and `LaunchedEffect`, it skips the initial composition, so it replaces the `var first by remember { mutableStateOf(true) }` guard that this behavior is usually hand-rolled with.

The single value overload also hands you the previous and the current value:

```kotlin
var count by remember { mutableIntStateOf(0) }
var enabled by remember { mutableStateOf(false) }

// Nothing is logged for the initial composition, only for the changes after it.
ChangedEffect(count) { previous, current ->
    Log.d(tag, "$previous -> $current")
}

// A single key takes a lambda without parameters, like the other effect APIs.
ChangedEffect(key1 = count) {
    Log.d(tag, "$count")
}

// Multiple keys are supported as well, and take a lambda without parameters.
ChangedEffect(count, enabled) {
    Log.d(tag, "$count, $enabled")
}

Button(onClick = { count++ }) {
    Text("Count: $count")
}
```

The effect runs in the apply phase, after the composition is committed, never during composition. A composition that is composed and then discarded instead of applied neither runs the effect nor moves the previous value forward.

### Lint

The `compose-effects` AAR ships an Android Lint check, so it activates on its own once you depend on the library. There is nothing to add to your build file.

| | |
|---|---|
| Artifact | `com.github.skydoves:compose-effects-lint` (published inside `compose-effects`) |
| Issue id | `LaunchedEffectWithoutSuspend` |
| Severity | Warning |

It flags a `LaunchedEffect` whose block never suspends and never touches its `CoroutineScope` receiver, and offers to rename the call to `RememberedEffect`, keeping the keys.

**Read the suggestion before taking it.** A `RememberedEffect` block runs synchronously from `RememberObserver.onRemembered()`, on the thread that applies the composition, and it is not cancelled when the keys change. That fits short, non-blocking work. A block that sleeps, blocks on I/O or loops forever belongs in `LaunchedEffect`, and the check cannot tell those apart from a cheap one. The quick fix is deliberately not an auto-fix for that reason, and it is withheld entirely for call shapes a rename would break (a fully qualified callee, a `block = ` named argument, or a file that already imports a different `RememberedEffect`).

The check stays quiet whenever it cannot resolve something in the block, so it under-reports rather than guessing. One case is worth knowing about: AGP 8.12 bundles a Kotlin 2.2 frontend for lint, which cannot read the metadata of a Kotlin 2.4 standard library. Kotlin standard library declarations that are **top-level or extensions** therefore do not resolve, and any block calling one is skipped. That covers `println`, `listOf`, `require` and `buildString`, and also extension members such as `String.uppercase()` or `Iterable.map { }`. Declarations that are plain JVM members resolve normally, as do your own top-level functions, whether declared in the same file or another one.

To turn it off:

```kotlin
android {
    lint {
        disable += "LaunchedEffectWithoutSuspend"
    }
}
```

## Compose Effects ViewModel

Compose Effects ViewModel provides side-effects/CompositionLocal APIs related to ViewModel.

[![Maven Central](https://img.shields.io/maven-central/v/com.github.skydoves/compose-effects-viewmodel.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22com.github.skydoves%22%20AND%20a:%22flow-operators%22)

### Gradle

Add the dependency below to your **module**'s `build.gradle.kts` file:

```gradle
dependencies {
    implementation("com.github.skydoves:compose-effects-viewmodel:$version")
}
```

### ViewModelStoreScope

In certain scenarios, managing ViewModel lifecycles at a more **Composable function-scoped** level is preferable to broader scopes like Activity or Jetpack Navigation. For example, you may need to assign dedicated ViewModel instances for **bottom sheets, dialogs inside a LazyColumn, or other complex UI components** to prevent unintended reuse of the same ViewModel across different scopes. This ensures better isolation and state management, particularly in cases where UI elements require independent lifecycle handling.

Consider the following scenario: you have a list of items, and clicking on an item opens a dialog specific to that item. Initially, everything appears to work fine. However, if you click on another item, you'll notice that the same ViewModel instance is being reused, regardless of how many times the dialog is dismissed. This can lead to unintended state persistence across different dialogs, affecting the expected behavior.

```kotlin
val items = List(50) { "item$it" }
var visibleDialog by remember { mutableStateOf(false) }

LazyColumn(modifier = Modifier.fillMaxSize()) {
  items(items = items, key = { it }) { item ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .height(500.dp)
        .clickable { visibleDialog = !visibleDialog }
    ) {
      Text(text = item)

      Box(
        modifier = Modifier
          .height(1.dp)
          .background(Color.Gray)
          .align(Alignment.BottomCenter)
      )
    }

    if (visibleDialog) {
      val vm: DialogViewModel = hiltViewModel() // reused
      val text by vm.state.collectAsState()

      Dialog(onDismissRequest = { visibleDialog = false }) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(450.dp)
            .background(Color.Blue)
            .clickable { vm.onClicked(item) }
        ) {
          Text(text = text, color = Color.White)
        }
      }
    }
  }
}
```

You can ensure that each dialog gets a new ViewModel instance by using `ViewModelStoreScope`, as demonstrated in the following code snippet:

```kotlin
ViewModelStoreScope(key = item) {
  if (visibleDialog) {
    val vm: DialogViewModel = hiltViewModel() // this will be scoped to the ViewModelStoreScope
    val text by vm.state.collectAsState()

    Dialog(onDismissRequest = { visibleDialog = false }) {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(450.dp)
          .background(Color.Blue)
          .clickable { vm.onClicked() }
      ) {
        Text(text = text, color = Color.White)
      }
    }
  }
}
```

ViewModelStoreScope is a disposable side-effect that creates a new `ViewModelStore` and `ViewModelStoreOwner`, scoping view models to a local store and ensuring the store is cleared when the it leaves the composition. When you need to scope ViewModels to a specific Composable-based lifecycle, `ViewModelStoreScope` provides an effective solution.

The scoped owner inherits the enclosing owner's default `ViewModelProvider.Factory`, which is what makes `hiltViewModel()` usable inside the scope: `HiltViewModelFactory` needs a real `SavedStateHandle` for every `@HiltViewModel`, whether or not it injects one. So the scope also runs its own `SavedStateRegistry` rather than borrowing the host's, which keeps sibling scopes from colliding and leaves the host's own saved state untouched.

Three consequences worth knowing. A `SavedStateHandle` obtained inside the scope lives as long as the scope and does **not** survive process death, the same lifetime as the scoped `ViewModelStore` itself. Inside the scope `LocalViewModelStoreOwner` is the scoped owner while `LocalSavedStateRegistryOwner` and `LocalLifecycleOwner` are still the host's, so read the registry owner off the scoped owner rather than pairing those two composition locals.

**Behavior change.** Under the default Compose host off Android, the default factory is `SavedStateViewModelFactory`, whose non-Android actual is an unimplemented stub, so a lookup with no explicit factory now throws inside the scope exactly as it already does outside it. Previously the scope fell through to the reflective default factory, so `viewModel<SomeNoArgViewModel>()` resolved on desktop where the host itself would have thrown. Pass a factory there, for example `viewModel { MyViewModel() }`. A host that supplies its own `HasDefaultViewModelProviderFactory` is unaffected.

## Find this repository useful? :heart:
Support it by joining __[stargazers](https://github.com/skydoves/compose-effects/stargazers)__ for this repository. :star: <br>
Also, __[follow me](https://github.com/skydoves)__ on GitHub for my next creations! 🤩

# License
```xml
Designed and developed by 2025 skydoves (Jaewoong Eum)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
