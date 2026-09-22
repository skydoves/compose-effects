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

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.LintFix
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.getParameterForArgument
import org.jetbrains.uast.tryResolve
import org.jetbrains.uast.visitor.AbstractUastVisitor

/**
 * Reports `LaunchedEffect` calls whose block never suspends and never touches its
 * `CoroutineScope` receiver, because such a block pays for a coroutine it does not use.
 */
public class LaunchedEffectWithoutSuspendDetector :
  Detector(),
  SourceCodeScanner {

  override fun getApplicableMethodNames(): List<String> = listOf(LAUNCHED_EFFECT_NAME)

  override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
    if (method.packageName() != COMPOSE_RUNTIME_PACKAGE) return

    val block = node.blockArgument() ?: return

    val scanner = CoroutineUsageScanner()
    block.accept(scanner)

    // A call that does not resolve could be anything, a suspend function included. Staying quiet
    // on a partial resolve trades a missed report for never emitting a wrong one, which matters
    // because this check reaches consumers through lintPublish rather than by their own choice.
    if (scanner.usesCoroutines || scanner.declined) return

    context.report(
      ISSUE,
      node,
      context.getNameLocation(node),
      "`LaunchedEffect` block never suspends, so `RememberedEffect` does the same work " +
        "without allocating a coroutine",
      replaceWithRememberedEffectFix(),
    )
  }

  /**
   * Returns the `block` lambda of a `LaunchedEffect` call, falling back to the trailing lambda
   * when the parameter cannot be matched by name.
   */
  private fun UCallExpression.blockArgument(): UExpression? =
    valueArguments.firstOrNull { getParameterForArgument(it)?.name == BLOCK_PARAMETER_NAME }
      ?: valueArguments.lastOrNull { it is ULambdaExpression }

  private fun replaceWithRememberedEffectFix(): LintFix = LintFix.create()
    .replace()
    .name("Replace with `RememberedEffect`")
    .text(LAUNCHED_EFFECT_NAME)
    .with(REMEMBERED_EFFECT_NAME)
    .imports(REMEMBERED_EFFECT_FQN)
    .autoFix()
    .build()

  /**
   * Accumulates the reasons a block genuinely needs a coroutine. Every signal here can only
   * suppress a report, so an over-eager match costs a missed warning and never a false one.
   */
  private class CoroutineUsageScanner : AbstractUastVisitor() {

    var usesCoroutines: Boolean = false
      private set

    var declined: Boolean = false
      private set

    override fun visitCallExpression(node: UCallExpression): Boolean {
      val resolved = node.resolve()
      if (resolved == null) {
        declined = true
        return false
      }
      if (resolved.isCoroutineRelated()) {
        usesCoroutines = true
      }
      return false
    }

    override fun visitSimpleNameReferenceExpression(node: USimpleNameReferenceExpression): Boolean {
      // `coroutineContext`, `isActive` and friends are property reads rather than calls, so
      // visitCallExpression never sees them even though they are CoroutineScope usage.
      when (val resolved = node.tryResolve()) {
        is PsiMethod -> if (resolved.isCoroutineRelated()) usesCoroutines = true
        is PsiMember -> if (resolved.declaredInCoroutineScope()) usesCoroutines = true
        else -> Unit
      }
      return false
    }

    private fun PsiMethod.isCoroutineRelated(): Boolean {
      if (hasContinuationParameter()) return true
      val packageName = packageName()
      if (packageName != null &&
        (packageName == COROUTINES_PACKAGE || packageName.startsWith("$COROUTINES_PACKAGE."))
      ) {
        return true
      }
      if (declaredInCoroutineScope()) return true
      return parameterList.parameters.any {
        InheritanceUtil.isInheritor(it.type, COROUTINE_SCOPE_FQN)
      }
    }

    /**
     * A Kotlin `suspend fun` is a plain JVM method with a trailing `Continuation` parameter, and
     * that shape survives both K1 and K2 UAST, unlike any source-level suspend flag.
     */
    private fun PsiMethod.hasContinuationParameter(): Boolean {
      val last = parameterList.parameters.lastOrNull() ?: return false
      return InheritanceUtil.isInheritor(last.type, CONTINUATION_FQN)
    }

    private fun PsiMember.declaredInCoroutineScope(): Boolean {
      val owner = containingClass ?: return false
      return InheritanceUtil.isInheritor(owner, COROUTINE_SCOPE_FQN)
    }
  }

  public companion object {

    private const val LAUNCHED_EFFECT_NAME = "LaunchedEffect"
    private const val REMEMBERED_EFFECT_NAME = "RememberedEffect"
    private const val BLOCK_PARAMETER_NAME = "block"
    private const val COMPOSE_RUNTIME_PACKAGE = "androidx.compose.runtime"
    private const val COROUTINES_PACKAGE = "kotlinx.coroutines"
    private const val COROUTINE_SCOPE_FQN = "kotlinx.coroutines.CoroutineScope"
    private const val CONTINUATION_FQN = "kotlin.coroutines.Continuation"
    private const val REMEMBERED_EFFECT_FQN = "com.skydoves.compose.effects.RememberedEffect"

    private fun PsiMember.packageName(): String? = (containingFile as? PsiClassOwner)?.packageName

    @JvmField
    public val ISSUE: Issue = Issue.create(
      id = "LaunchedEffectWithoutSuspend",
      briefDescription = "LaunchedEffect block does not need a coroutine",
      explanation = "`LaunchedEffect` starts a coroutine on every key change, even when the " +
        "block neither suspends nor uses its `CoroutineScope` receiver. " +
        "`RememberedEffect` runs the block from `RememberObserver.onRemembered()`, which the " +
        "Compose runtime invokes in the apply phase, so it keeps the same guarantee of only " +
        "running after a successful composition while skipping the coroutine allocation and " +
        "its cancellation bookkeeping. Replace the call with `RememberedEffect`, keeping the " +
        "same keys.",
      category = Category.PERFORMANCE,
      priority = 5,
      // WARNING, not ERROR: this check is delivered through lintPublish, so it lands in builds
      // that never asked for it. An ERROR would fail a consumer's release over a style call.
      severity = Severity.WARNING,
      implementation = Implementation(
        LaunchedEffectWithoutSuspendDetector::class.java,
        Scope.JAVA_FILE_SCOPE,
      ),
    )
  }
}
