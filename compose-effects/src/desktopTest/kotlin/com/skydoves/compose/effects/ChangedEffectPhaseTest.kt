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

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ControlledComposition
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An applier that builds nothing: these tests only observe when effects run, not what is applied
 * to a tree.
 */
private object NoOpApplier : AbstractApplier<Unit>(Unit) {
  override fun insertTopDown(index: Int, instance: Unit) = Unit
  override fun insertBottomUp(index: Int, instance: Unit) = Unit
  override fun remove(index: Int, count: Int) = Unit
  override fun move(from: Int, to: Int, count: Int) = Unit
  override fun onClear() = Unit
}

/**
 * Runs a composition pass the way [Recomposer] runs it, inside a mutable snapshot that reports
 * reads and writes back to the composition. Without it the composition records no state reads,
 * `recordModificationsOf` invalidates nothing, and `recompose` silently does no work.
 */
private fun <R> ControlledComposition.composing(block: ControlledComposition.() -> R): R {
  val snapshot = Snapshot.takeMutableSnapshot(
    readObserver = { recordReadOf(it) },
    writeObserver = { recordWriteOf(it) },
  )
  try {
    return snapshot.enter { block() }
  } finally {
    try {
      snapshot.apply().check()
    } finally {
      snapshot.dispose()
    }
  }
}

/**
 * JVM-only tests that pin down *when* [ChangedEffect] runs, which is the reason it is built on
 * [androidx.compose.runtime.RememberObserver] instead of a `remember(key) { effect() }`
 * calculation.
 *
 * A calculation block runs in the composition phase, so a composition that is composed and then
 * discarded instead of applied would have fired the side effect anyway, and would have advanced
 * the remembered previous value to one that never reached the screen.
 * `RememberObserver.onRemembered` runs in the apply phase, so a discarded composition gets
 * `onAbandoned` instead and nothing is observed.
 */
@OptIn(ExperimentalTestApi::class)
class ChangedEffectPhaseTest {

  /**
   * The effect is written first in the source but must be logged after the `remember` calculation
   * below it, because the calculation runs in the composition phase and the effect runs in the
   * apply phase, alongside `DisposableEffect`.
   */
  @Test
  fun runsInTheApplyPhaseNotInTheCompositionPhase() = runComposeUiTest {
    val log = mutableListOf<String>()
    val key = mutableIntStateOf(0)

    setContent {
      ChangedEffect(key.intValue) { log += "ChangedEffect" }
      remember(key.intValue) { log += "plainRemember" }
      DisposableEffect(key.intValue) {
        log += "DisposableEffect"
        onDispose { }
      }
    }
    waitForIdle()

    // The initial composition skips the effect, but still proves the other two ran.
    assertEquals(listOf("plainRemember", "DisposableEffect"), log)

    log.clear()
    key.intValue = 1
    waitForIdle()

    assertEquals(listOf("plainRemember", "ChangedEffect", "DisposableEffect"), log)
  }

  /**
   * A composition that is composed but never applied must not run the effect, and must not move
   * the remembered previous value forward: the next applied change still reports the last value
   * that actually reached the screen.
   */
  @Test
  fun doesNotRunOrAdvanceWhenTheCompositionIsNeverApplied() {
    val observed = mutableListOf<String>()
    var plainRememberRan = false
    val value = mutableIntStateOf(0)

    val recomposer = Recomposer(EmptyCoroutineContext)
    val composition = ControlledComposition(NoOpApplier, recomposer)
    val content = @Composable {
      ChangedEffect(value.intValue) { previous, current -> observed += "$previous->$current" }
      remember(value.intValue) { plainRememberRan = true }
    }

    try {
      composition.composing { composeContent(content) }
      composition.applyChanges()
      assertTrue(plainRememberRan, "the initial composition did not run at all")
      assertEquals(emptyList(), observed)

      // Composed, then abandoned instead of applied.
      plainRememberRan = false
      value.intValue = 1
      composition.recordModificationsOf(setOf(value))
      assertTrue(composition.composing { recompose() }, "the abandoned pass had nothing to apply")
      composition.abandonChanges()

      // Positive control: the abandoned pass really did compose, so an effect running in the
      // composition phase would have been observed here.
      assertTrue(plainRememberRan, "the abandoned pass did not compose at all")
      assertEquals(emptyList(), observed)

      // The previous value must still be 0, the last value that was actually applied.
      value.intValue = 2
      composition.recordModificationsOf(setOf(value))
      assertTrue(composition.composing { recompose() }, "the final pass had nothing to apply")
      composition.applyChanges()
      assertEquals(listOf("0->2"), observed)
    } finally {
      composition.dispose()
      recomposer.cancel()
    }
  }
}
