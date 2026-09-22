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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Multiplatform behavioral tests for [ChangedEffect], executed on desktop/JVM, the iOS simulator,
 * and macOS. They lock in the contract that separates it from [RememberedEffect]: the initial
 * composition is skipped, recompositions with unchanged keys are skipped, and the effect runs only
 * when a key or value actually changes.
 */
@OptIn(ExperimentalTestApi::class)
class ChangedEffectTest {

  @Test
  fun doesNotRunOnInitialComposition() = runComposeUiTest {
    var valueRunCount = 0
    var keysRunCount = 0

    setContent {
      ChangedEffect(value = "initial") { _, _ -> valueRunCount++ }
      ChangedEffect("initial", "initial") { keysRunCount++ }
    }
    waitForIdle()

    assertEquals(0, valueRunCount)
    assertEquals(0, keysRunCount)
  }

  @Test
  fun runsWithPreviousAndCurrentWhenValueChanges() = runComposeUiTest {
    val observed = mutableListOf<String>()
    val value = mutableIntStateOf(0)

    setContent {
      ChangedEffect(value.intValue) { previous, current -> observed += "$previous->$current" }
    }
    waitForIdle()
    assertEquals(emptyList(), observed)

    value.intValue = 1
    waitForIdle()
    assertEquals(listOf("0->1"), observed)

    value.intValue = 7
    waitForIdle()
    assertEquals(listOf("0->1", "1->7"), observed)
  }

  @Test
  fun doesNotReRunAcrossRecompositionsWhenValueIsUnchanged() = runComposeUiTest {
    val observed = mutableListOf<String>()
    val value = mutableStateOf("a")
    val tick = mutableIntStateOf(0)

    setContent {
      // Subscribe the root recompose scope to `tick` so we can force recompositions
      // without touching the effect's value.
      tick.intValue
      ChangedEffect(value.value) { previous, current -> observed += "$previous->$current" }
    }
    waitForIdle()
    assertEquals(emptyList(), observed)

    repeat(3) {
      tick.intValue++
      waitForIdle()
    }

    // Recomposition alone must not execute the effect.
    assertEquals(emptyList(), observed)

    // The effect is still armed: a real change runs it exactly once, from the value that the
    // recompositions left in place.
    value.value = "b"
    waitForIdle()
    assertEquals(listOf("a->b"), observed)
  }

  @Test
  fun twoKeysReRunOnChangeAndSkipTheInitialComposition() = runComposeUiTest {
    var runCount = 0
    val key1 = mutableIntStateOf(0)
    val key2 = mutableIntStateOf(0)

    setContent {
      ChangedEffect(key1.intValue, key2.intValue) { runCount++ }
    }
    waitForIdle()
    assertEquals(0, runCount)

    key1.intValue = 1
    waitForIdle()
    assertEquals(1, runCount)

    key2.intValue = 1
    waitForIdle()
    assertEquals(2, runCount)
  }

  @Test
  fun threeKeysReRunOnChangeAndSkipTheInitialComposition() = runComposeUiTest {
    var runCount = 0
    val key1 = mutableIntStateOf(0)
    val key2 = mutableIntStateOf(0)
    val key3 = mutableIntStateOf(0)

    setContent {
      ChangedEffect(key1.intValue, key2.intValue, key3.intValue) { runCount++ }
    }
    waitForIdle()
    assertEquals(0, runCount)

    key2.intValue = 1
    waitForIdle()
    assertEquals(1, runCount)

    key3.intValue = 1
    waitForIdle()
    assertEquals(2, runCount)
  }

  @Test
  fun varargKeysReRunOnChangeAndSkipTheInitialComposition() = runComposeUiTest {
    var runCount = 0
    val a = mutableIntStateOf(0)
    val b = mutableIntStateOf(0)
    val c = mutableIntStateOf(0)

    setContent {
      ChangedEffect(a.intValue, b.intValue, c.intValue, "constant") { runCount++ }
    }
    waitForIdle()
    assertEquals(0, runCount)

    c.intValue = 5
    waitForIdle()
    assertEquals(1, runCount)

    a.intValue = 5
    waitForIdle()
    assertEquals(2, runCount)
  }

  /**
   * Pins the overload resolution for a single key, which is the one shape where the overloads
   * compete: a lambda without parameters can only satisfy the single key overload's `() -> Unit`
   * and a lambda with two can only satisfy the single value overload's
   * `(previous: T, current: T) -> Unit`, so the calls below compile only when each of them picks
   * a different overload. The named argument and the lambda held in a variable are the two shapes
   * a reader of the `RememberedEffect(key1 = ...)` documentation reaches for first, and neither
   * of them resolves against a vararg parameter.
   */
  @Test
  fun singleKeyResolvesByLambdaArity() = runComposeUiTest {
    var zeroArgRunCount = 0
    var namedKeyRunCount = 0
    var lambdaVariableRunCount = 0
    val observed = mutableListOf<String>()
    val key = mutableStateOf("a")
    val storedEffect: () -> Unit = { lambdaVariableRunCount++ }

    setContent {
      ChangedEffect(key.value) { zeroArgRunCount++ }
      ChangedEffect(key1 = key.value) { namedKeyRunCount++ }
      ChangedEffect(key.value, storedEffect)
      ChangedEffect(key.value) { previous, current -> observed += "$previous->$current" }
    }
    waitForIdle()
    assertEquals(0, zeroArgRunCount)
    assertEquals(0, namedKeyRunCount)
    assertEquals(0, lambdaVariableRunCount)
    assertEquals(emptyList(), observed)

    key.value = "b"
    waitForIdle()
    assertEquals(1, zeroArgRunCount)
    assertEquals(1, namedKeyRunCount)
    assertEquals(1, lambdaVariableRunCount)
    assertEquals(listOf("a->b"), observed)
  }
}
