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
composeEffects = "0.1.0"

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
