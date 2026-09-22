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
package com.skydoves.compose.effects.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * The corpus below is derived from the branches of [LaunchedEffectWithoutSuspendDetector]:
 * one fixture per way the scanner can decide "this block needs a coroutine", one per bail-out,
 * and one per shape where a rename would produce code that does not compile.
 */
@RunWith(JUnit4::class)
class LaunchedEffectWithoutSuspendDetectorTest : LintDetectorTest() {

  override fun getDetector(): Detector = LaunchedEffectWithoutSuspendDetector()

  override fun getIssues(): List<Issue> = listOf(LaunchedEffectWithoutSuspendDetector.ISSUE)

  private val coroutinesStub: TestFile = kotlin(
    """
    package kotlinx.coroutines

    import kotlin.coroutines.CoroutineContext

    interface CoroutineScope {
      val coroutineContext: CoroutineContext
    }

    interface Job

    object Dispatchers {
      val IO: CoroutineContext get() = TODO()
    }

    suspend fun delay(timeMillis: Long) {}

    suspend fun CoroutineScope.work() {}

    suspend fun <T> withContext(
      context: CoroutineContext,
      block: suspend CoroutineScope.() -> T
    ): T = TODO()

    fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Job = TODO()
    """,
  ).indented()

  private val flowStub: TestFile = kotlin(
    """
    package kotlinx.coroutines.flow

    interface FlowCollector<in T> {
      suspend fun emit(value: T)
    }

    interface Flow<out T> {
      suspend fun collect(collector: FlowCollector<T>)
    }

    suspend fun <T> Flow<T>.collect(action: suspend (T) -> Unit) {}
    """,
  ).indented()

  private val composeRuntimeStub: TestFile = kotlin(
    """
    package androidx.compose.runtime

    import kotlinx.coroutines.CoroutineScope

    annotation class Composable

    @Composable
    fun LaunchedEffect(key1: Any?, block: suspend CoroutineScope.() -> Unit) {}

    @Composable
    fun LaunchedEffect(
      key1: Any?,
      key2: Any?,
      block: suspend CoroutineScope.() -> Unit
    ) {}
    """,
  ).indented()

  private fun check(source: TestFile, vararg extra: TestFile): TestLintResult = lint()
    .files(coroutinesStub, flowStub, composeRuntimeStub, *extra, source)
    .allowMissingSdk()
    .run()

  // region MUST WARN

  @Test
  fun `warns when the block only makes a non-suspend call`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        fun log() {}

        @Composable
        fun Screen() {
          LaunchedEffect(Unit) {
            log()
          }
        }
        """,
      ).indented(),
    ).expect(warningAt(line = 10, source = "  LaunchedEffect(Unit) {"))
  }

  @Test
  fun `warns when a multi key call has a non-suspend block`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        fun log() {}

        @Composable
        fun Screen(a: Int, b: Int) {
          LaunchedEffect(a, b) {
            log()
          }
        }
        """,
      ).indented(),
    ).expect(warningAt(line = 10, source = "  LaunchedEffect(a, b) {"))
  }

  @Test
  fun `warns when both branches of an if are non-suspend`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        fun onTrue() {}

        fun onFalse() {}

        @Composable
        fun Screen(flag: Boolean) {
          LaunchedEffect(flag) {
            if (flag) {
              onTrue()
            } else {
              onFalse()
            }
          }
        }
        """,
      ).indented(),
    ).expect(warningAt(line = 12, source = "  LaunchedEffect(flag) {"))
  }

  @Test
  fun `warns when the block is empty`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        @Composable
        fun Screen() {
          LaunchedEffect(Unit) {
          }
        }
        """,
      ).indented(),
    ).expect(warningAt(line = 8, source = "  LaunchedEffect(Unit) {"))
  }

  /**
   * The control for the B1 family below: a plain function-type parameter carries no coroutine,
   * so widening the suspend-type check must not swallow this one.
   */
  @Test
  fun `warns when the block invokes a non-suspend function type parameter`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        @Composable
        fun Screen(k: Int, onEvent: () -> Unit) {
          LaunchedEffect(k) {
            onEvent()
          }
        }
        """,
      ).indented(),
    ).expect(warningAt(line = 8, source = "  LaunchedEffect(k) {"))
  }

  // endregion

  // region MUST NOT WARN: the block genuinely needs a coroutine

  @Test
  fun `clean when the block calls delay`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect
        import kotlinx.coroutines.delay

        @Composable
        fun Screen() {
          LaunchedEffect(Unit) {
            delay(100)
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when the block calls withContext`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect
        import kotlinx.coroutines.Dispatchers
        import kotlinx.coroutines.withContext

        @Composable
        fun Screen() {
          LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
            }
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when the block launches on its receiver scope`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect
        import kotlinx.coroutines.launch

        @Composable
        fun Screen() {
          LaunchedEffect(Unit) {
            launch {
            }
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when the block reads coroutineContext`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        fun log(value: Any?) {}

        @Composable
        fun Screen() {
          LaunchedEffect(Unit) {
            log(coroutineContext)
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when the block collects a flow`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect
        import kotlinx.coroutines.flow.Flow
        import kotlinx.coroutines.flow.collect

        @Composable
        fun Screen(flow: Flow<Int>) {
          LaunchedEffect(flow) {
            flow.collect {
            }
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when the block calls a locally declared suspend function`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        suspend fun loadUser(): String = ""

        @Composable
        fun Screen() {
          LaunchedEffect(Unit) {
            loadUser()
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when a suspend call is nested inside another lambda`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect
        import kotlinx.coroutines.delay

        @Composable
        fun Screen(items: List<Int>) {
          LaunchedEffect(items) {
            items.forEach {
              delay(1)
            }
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when the receiver scope is handed to a CoroutineScope parameter`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect
        import kotlinx.coroutines.CoroutineScope

        fun observe(scope: CoroutineScope) {}

        @Composable
        fun Screen() {
          LaunchedEffect(Unit) {
            observe(this)
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  /**
   * S1: the fixture above passes for the wrong reason if the helper is typed `CoroutineScope`.
   * Widening the parameter to `Any` removes that crutch, so only receiver detection can save it.
   */
  @Test
  fun `clean when the labeled receiver is handed to an Any parameter`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        fun register(value: Any) {}

        @Composable
        fun Screen(k: Int) {
          LaunchedEffect(k) {
            register(this@LaunchedEffect)
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  /** S1: the receiver escaping into a field is the same capture with no call to inspect. */
  @Test
  fun `clean when the receiver is stored in a field`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        object Holder {
          var owner: Any? = null
        }

        @Composable
        fun Screen(k: Int) {
          LaunchedEffect(k) {
            Holder.owner = this
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  // endregion

  // region MUST NOT WARN: B1, suspend-ness carried by a function type rather than a method

  @Test
  fun `clean when the block invokes a suspend function type parameter`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        @Composable
        fun Screen(k: Int, onEvent: suspend () -> Unit) {
          LaunchedEffect(k) {
            onEvent()
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when the block invokes a suspend CoroutineScope receiver parameter`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect
        import kotlinx.coroutines.CoroutineScope

        @Composable
        fun Screen(k: Int, b: suspend CoroutineScope.() -> Unit) {
          LaunchedEffect(k) {
            b()
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when the block invokes a suspend top level val`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        val handler: suspend () -> Unit = {}

        @Composable
        fun Screen(k: Int) {
          LaunchedEffect(k) {
            handler()
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when the block invokes a suspend local val`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        @Composable
        fun Screen(k: Int) {
          LaunchedEffect(k) {
            val handler: suspend () -> Unit = {}
            handler()
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  // endregion

  // region MUST NOT WARN: bail-outs

  @Test
  fun `clean when the block calls something that does not resolve`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        @Composable
        fun Screen() {
          LaunchedEffect(Unit) {
            totallyUnknownHelper()
          }
        }
        """,
      ).indented(),
    ).expectClean()
  }

  /** S2: a callable reference hides every call from the visitor, so nothing can be concluded. */
  @Test
  fun `clean when the block argument is a callable reference`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect
        import kotlinx.coroutines.CoroutineScope
        import kotlinx.coroutines.work

        @Composable
        fun Screen(k: Int) {
          LaunchedEffect(k, block = CoroutineScope::work)
        }
        """,
      ).indented(),
    ).expectClean()
  }

  @Test
  fun `clean when LaunchedEffect comes from another package`() {
    check(
      kotlin(
        """
        package com.example

        import com.other.LaunchedEffect

        fun log() {}

        fun screen() {
          LaunchedEffect(Unit) {
            log()
          }
        }
        """,
      ).indented(),
      kotlin(
        """
        package com.other

        fun LaunchedEffect(key1: Any?, block: () -> Unit) {}
        """,
      ).indented(),
    ).expectClean()
  }

  // endregion

  // region quick fix safety

  /**
   * Two of lint's own test modes rewrite this call into exactly the shapes the fixtures below
   * assert get no fix: FULLY_QUALIFIED prefixes the callee, and REORDER_ARGUMENTS turns the
   * trailing lambda into a `block = ` argument. Both are covered by their own fixtures, so
   * skipping them here narrows this assertion to the safe shape instead of hiding anything.
   */
  @Test
  fun `quick fix renames the call and imports RememberedEffect`() {
    lint()
      .files(
        coroutinesStub,
        flowStub,
        composeRuntimeStub,
        kotlin(
          """
          package com.example

          import androidx.compose.runtime.Composable
          import androidx.compose.runtime.LaunchedEffect

          fun log() {}

          @Composable
          fun Screen() {
            LaunchedEffect(Unit) {
              log()
            }
          }
          """,
        ).indented(),
      )
      .allowMissingSdk()
      .skipTestModes(TestMode.FULLY_QUALIFIED, TestMode.REORDER_ARGUMENTS)
      .run()
      .expect(warningAt(line = 10, source = "  LaunchedEffect(Unit) {"))
      .expectFixDiffs(
        """
        Fix for src/com/example/test.kt line 10: Replace with `RememberedEffect`:
        @@ -4,0 +5 @@
        +import com.skydoves.compose.effects.RememberedEffect
        @@ -10 +11 @@
        -  LaunchedEffect(Unit) {
        +  RememberedEffect(Unit) {
        """.trimIndent(),
      )
  }

  /**
   * B2 row 1: renaming only the identifier of a fully qualified call yields
   * `androidx.compose.runtime.RememberedEffect`, which does not exist.
   */
  @Test
  fun `no quick fix when the call is fully qualified`() {
    check(
      kotlin(
        """
        package com.example

        fun log() {}

        @androidx.compose.runtime.Composable
        fun Screen(k: Int) {
          androidx.compose.runtime.LaunchedEffect(k) {
            log()
          }
        }
        """,
      ).indented(),
    ).expect(
      warningAt(line = 7, source = "  androidx.compose.runtime.LaunchedEffect(k) {"),
    ).expectFixDiffs("")
  }

  /**
   * B2 row 2: `RememberedEffect` names its lambda `effect`, so a `block = ` argument would bind
   * to the deprecated zero-key overload instead of failing loudly.
   */
  @Test
  fun `no quick fix when the block argument is named`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect

        fun log() {}

        @Composable
        fun Screen(k: Int) {
          LaunchedEffect(k, block = { log() })
        }
        """,
      ).indented(),
    ).expect(
      warningAt(line = 10, source = "  LaunchedEffect(k, block = { log() })"),
    ).expectFixDiffs("")
  }

  /** N2: a second import of the same simple name compiles and silently binds elsewhere. */
  @Test
  fun `no quick fix when a different RememberedEffect is already imported`() {
    check(
      kotlin(
        """
        package com.example

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.LaunchedEffect
        import com.other.RememberedEffect

        fun log() {}

        @Composable
        fun Screen(k: Int) {
          RememberedEffect()
          LaunchedEffect(k) {
            log()
          }
        }
        """,
      ).indented(),
      kotlin(
        """
        package com.other

        fun RememberedEffect() {}
        """,
      ).indented(),
    ).expect(
      warningAt(line = 12, source = "  LaunchedEffect(k) {"),
    ).expectFixDiffs("")
  }

  // endregion

  private companion object {

    /**
     * Written out by hand rather than read from the detector, so a message change has to be a
     * deliberate edit in two places instead of silently re-baselining itself. Lint's text
     * report drops the backticks the issue message carries for IDE rendering.
     */
    private const val MESSAGE = "LaunchedEffect block never suspends; consider " +
      "RememberedEffect, which runs the block without a coroutine"

    private const val CALLEE_LENGTH = "LaunchedEffect".length

    private fun warningAt(line: Int, source: String): String = buildString {
      append("src/com/example/test.kt:")
      append(line)
      append(": Warning: ")
      append(MESSAGE)
      append(" [LaunchedEffectWithoutSuspend]\n")
      append(source)
      append("\n")
      append(" ".repeat(source.indexOf("LaunchedEffect")))
      append("~".repeat(CALLEE_LENGTH))
      append("\n")
      append("0 errors, 1 warnings")
    }
  }
}
