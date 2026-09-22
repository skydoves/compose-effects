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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember

private const val CHANGED_EFFECT_NO_PARAM_ERROR =
  "ChangedEffect must provide one or more 'key' parameters."

/**
 * It is an error to call [ChangedEffect] without at least one `key` parameter.
 */
// This deprecated-error function shadows the varargs overload so that the varargs version
// is not used without key parameters.
@Deprecated(CHANGED_EFFECT_NO_PARAM_ERROR, level = DeprecationLevel.ERROR)
@Suppress("DeprecatedCallableAddReplaceWith", "UNUSED_PARAMETER")
@Composable
public fun ChangedEffect(effect: () -> Unit): Unit = error(CHANGED_EFFECT_NO_PARAM_ERROR)

/**
 * `ChangedEffect` is a side-effect API that executes the provided [effect] lambda only when
 * [value] actually changes, handing it both the `previous` and the `current` value.
 *
 * Unlike [RememberedEffect], the initial composition is skipped: the first pass only records
 * [value], so [effect] runs from the first change onwards. It is the "react to a change" API
 * that is otherwise hand-rolled with a `var first by remember { mutableStateOf(true) }` guard.
 *
 * Leaving the composition resets it. On re-entry the first pass only records [value] again, so a
 * change that happened while it was away is not reported.
 *
 * The [effect] runs in the apply phase, after the composition is committed, never during
 * composition. A composition that is composed and then discarded instead of applied neither runs
 * it nor advances the remembered `previous` value.
 */
@Composable
@NonRestartableComposable
public fun <T> ChangedEffect(value: T, effect: (previous: T, current: T) -> Unit) {
  val holder = remember { ChangedEffectHolder() }
  remember(value) { ChangedValueEffectImpl(holder = holder, current = value, effect = effect) }
}

/**
 * `ChangedEffect` is a side-effect API that executes the provided [effect] lambda only when [key1]
 * actually changes.
 *
 * Unlike [RememberedEffect], the initial composition is skipped: the first pass only records the
 * key, so [effect] runs from the first key change onwards. Take the single value overload instead
 * when the effect needs the previous value as well as the current one.
 *
 * Leaving the composition resets it. On re-entry the first pass only records the key again, so a
 * change that happened while it was away is not reported.
 *
 * The [effect] runs in the apply phase, after the composition is committed, never during
 * composition. A composition that is composed and then discarded instead of applied neither runs
 * it nor consumes the initial pass.
 */
@Composable
@NonRestartableComposable
public fun ChangedEffect(key1: Any?, effect: () -> Unit) {
  val holder = remember { ChangedEffectHolder() }
  remember(key1) { ChangedKeysEffectImpl(holder = holder, effect = effect) }
}

/**
 * `ChangedEffect` is a side-effect API that executes the provided [effect] lambda only when any
 * of [key1] and [key2] actually changes.
 *
 * Unlike [RememberedEffect], the initial composition is skipped: the first pass only records the
 * keys, so [effect] runs from the first key change onwards.
 *
 * Leaving the composition resets it. On re-entry the first pass only records the keys again, so a
 * change that happened while it was away is not reported.
 *
 * The [effect] runs in the apply phase, after the composition is committed, never during
 * composition. A composition that is composed and then discarded instead of applied neither runs
 * it nor consumes the initial pass.
 */
@Composable
@NonRestartableComposable
public fun ChangedEffect(key1: Any?, key2: Any?, effect: () -> Unit) {
  val holder = remember { ChangedEffectHolder() }
  remember(key1, key2) { ChangedKeysEffectImpl(holder = holder, effect = effect) }
}

/**
 * `ChangedEffect` is a side-effect API that executes the provided [effect] lambda only when any
 * of [key1], [key2], and [key3] actually changes.
 *
 * Unlike [RememberedEffect], the initial composition is skipped: the first pass only records the
 * keys, so [effect] runs from the first key change onwards.
 *
 * Leaving the composition resets it. On re-entry the first pass only records the keys again, so a
 * change that happened while it was away is not reported.
 *
 * The [effect] runs in the apply phase, after the composition is committed, never during
 * composition. A composition that is composed and then discarded instead of applied neither runs
 * it nor consumes the initial pass.
 */
@Composable
@NonRestartableComposable
public fun ChangedEffect(key1: Any?, key2: Any?, key3: Any?, effect: () -> Unit) {
  val holder = remember { ChangedEffectHolder() }
  remember(key1, key2, key3) { ChangedKeysEffectImpl(holder = holder, effect = effect) }
}

/**
 * `ChangedEffect` is a side-effect API that executes the provided [effect] lambda only when any
 * of [keys] actually changes.
 *
 * Unlike [RememberedEffect], the initial composition is skipped: the first pass only records the
 * keys, so [effect] runs from the first key change onwards.
 *
 * Leaving the composition resets it. On re-entry the first pass only records the keys again, so a
 * change that happened while it was away is not reported.
 *
 * The [effect] runs in the apply phase, after the composition is committed, never during
 * composition. A composition that is composed and then discarded instead of applied neither runs
 * it nor consumes the initial pass.
 */
@Composable
@NonRestartableComposable
public fun ChangedEffect(vararg keys: Any?, effect: () -> Unit) {
  val holder = remember { ChangedEffectHolder() }
  remember(*keys) { ChangedKeysEffectImpl(holder = holder, effect = effect) }
}

/**
 * Survives key changes so the effect implementations can tell the initial pass apart from a real
 * change. [previous] is written by the single value overload only, which is the one that reports
 * it; the key based overloads need [hasComposed] alone.
 */
internal class ChangedEffectHolder {

  var hasComposed: Boolean = false

  var previous: Any? = null
}

/**
 * Invokes the provided [effect] with the previous and the current value whenever the value
 * changes, skipping the initial composition.
 *
 * The holder is advanced from [onRemembered] only, which runs in the apply phase. A composition
 * that is composed but never applied receives [onAbandoned] instead, so it neither runs the
 * effect nor moves the previous value forward.
 */
internal class ChangedValueEffectImpl<T>(
  private val holder: ChangedEffectHolder,
  private val current: T,
  private val effect: (previous: T, current: T) -> Unit,
) : RememberObserver {

  override fun onRemembered() {
    if (holder.hasComposed) {
      @Suppress("UNCHECKED_CAST")
      effect.invoke(holder.previous as T, current)
    }
    holder.hasComposed = true
    holder.previous = current
  }

  override fun onAbandoned() {
    // no-op
  }

  override fun onForgotten() {
    // no-op
  }
}

/**
 * Invokes the provided [effect] whenever the keys change, skipping the initial composition.
 *
 * The holder is advanced from [onRemembered] only, which runs in the apply phase. A composition
 * that is composed but never applied receives [onAbandoned] instead, so it neither runs the
 * effect nor consumes the initial pass.
 */
internal class ChangedKeysEffectImpl(
  private val holder: ChangedEffectHolder,
  private val effect: () -> Unit,
) : RememberObserver {

  override fun onRemembered() {
    if (holder.hasComposed) {
      effect.invoke()
    }
    holder.hasComposed = true
  }

  override fun onAbandoned() {
    // no-op
  }

  override fun onForgotten() {
    // no-op
  }
}
