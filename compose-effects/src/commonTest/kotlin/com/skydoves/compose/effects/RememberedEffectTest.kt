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
package com.skydoves.compose.effects

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioral tests for [RememberedEffect]. They lock in the same semantics as
 * `LaunchedEffect`/`DisposableEffect` minus the coroutine: the effect runs once on entering the
 * composition, is skipped across recompositions while the keys are unchanged, and re-runs only
 * when a key changes.
 *
 * They live in `commonTest` so they compile for every target, but only desktop/JVM, the iOS
 * simulator and macOS execute them today. The Android unit test variant has no Robolectric, so
 * every case fails reading `Build.FINGERPRINT`, and the JS test task cannot be configured while
 * `settings.gradle.kts` prefers settings repositories.
 */
@OptIn(ExperimentalTestApi::class)
class RememberedEffectTest {

  @Test
  fun runsOnceWhenEnteringComposition() = runComposeUiTest {
    var runCount = 0

    setContent {
      RememberedEffect(key1 = Unit) { runCount++ }
    }
    waitForIdle()

    assertEquals(1, runCount)
  }

  @Test
  fun doesNotReRunAcrossRecompositionsWhenKeyIsUnchanged() = runComposeUiTest {
    var runCount = 0
    val tick = mutableIntStateOf(0)

    setContent {
      // Subscribe the root recompose scope to `tick` so we can force recompositions
      // without touching the effect's key.
      tick.intValue
      RememberedEffect(key1 = "stable-key") { runCount++ }
    }
    waitForIdle()
    assertEquals(1, runCount)

    repeat(3) {
      tick.intValue++
      waitForIdle()
    }

    // Recomposition alone must not re-execute the effect.
    assertEquals(1, runCount)
  }

  @Test
  fun reRunsWhenSingleKeyChanges() = runComposeUiTest {
    var runCount = 0
    val key = mutableIntStateOf(0)

    setContent {
      RememberedEffect(key1 = key.intValue) { runCount++ }
    }
    waitForIdle()
    assertEquals(1, runCount)

    key.intValue = 1
    waitForIdle()
    assertEquals(2, runCount)

    key.intValue = 2
    waitForIdle()
    assertEquals(3, runCount)
  }

  @Test
  fun reRunsWhenAnyOfMultipleKeysChange() = runComposeUiTest {
    var runCount = 0
    val key1 = mutableIntStateOf(0)
    val key2 = mutableIntStateOf(0)

    setContent {
      RememberedEffect(key1.intValue, key2.intValue) { runCount++ }
    }
    waitForIdle()
    assertEquals(1, runCount)

    key1.intValue = 1
    waitForIdle()
    assertEquals(2, runCount)

    key2.intValue = 1
    waitForIdle()
    assertEquals(3, runCount)
  }

  @Test
  fun reRunsWhenVarargKeysChange() = runComposeUiTest {
    var runCount = 0
    val a = mutableIntStateOf(0)
    val b = mutableIntStateOf(0)
    val c = mutableIntStateOf(0)

    setContent {
      RememberedEffect(a.intValue, b.intValue, c.intValue, "constant") { runCount++ }
    }
    waitForIdle()
    assertEquals(1, runCount)

    c.intValue = 5
    waitForIdle()
    assertEquals(2, runCount)
  }
}
