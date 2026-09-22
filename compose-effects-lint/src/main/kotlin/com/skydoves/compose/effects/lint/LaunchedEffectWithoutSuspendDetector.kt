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
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMember
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.InheritanceUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UImportStatement
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UThisExpression
import org.jetbrains.uast.getContainingUFile
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

    // Anything but a lambda (a callable reference, say) hides every call from the scanner, so
    // "no coroutine usage found" would mean "nothing was examined" rather than a finding.
    val block = node.blockArgument() as? ULambdaExpression ?: return

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
      "`LaunchedEffect` block never suspends; consider `RememberedEffect`, which runs the " +
        "block without a coroutine",
      // No fix at all beats a fix that does not compile, so the rename is offered only for the
      // shape it is actually correct for.
      node.renameFixOrNull(context),
    )
  }

  /**
   * Returns the `block` argument of a `LaunchedEffect` call, falling back to the last argument
   * when the parameter cannot be matched by name.
   */
  private fun UCallExpression.blockArgument() =
    valueArguments.firstOrNull { getParameterForArgument(it)?.name == BLOCK_PARAMETER_NAME }
      ?: valueArguments.lastOrNull()

  /**
   * The fix rewrites one identifier, so it is correct only where that is the whole edit. It is
   * withheld, rather than adjusted, for every other shape:
   * - a qualified callee would become `androidx.compose.runtime.RememberedEffect`, which does
   *   not exist;
   * - `RememberedEffect` names its lambda `effect`, so a `block = ` argument would silently
   *   bind to the deprecated zero-key overload;
   * - a second import of the same simple name compiles and binds to the other declaration;
   * - an aliased import leaves no `LaunchedEffect` text at the call site, and lint's fix
   *   performer refuses a replacement whose search text is not there.
   */
  private fun UCallExpression.renameFixOrNull(context: JavaContext): LintFix? {
    if (receiver != null) return null

    val call = sourcePsi as? KtCallExpression ?: return null
    if (call.calleeExpression?.text != LAUNCHED_EFFECT_NAME) return null
    if (call.lambdaArguments.size != 1) return null
    if (call.valueArguments.any { it.getArgumentName() != null }) return null

    // An unqualified call always imports its callee, so an empty import list means the file is
    // not the shape this fix reasons about.
    val imports = getContainingUFile()?.imports.orEmpty()
    if (imports.isEmpty()) return null
    val references = imports.map { it to it.importReference?.asSourceString().orEmpty() }

    if (references.any { (_, text) ->
        text.substringAfterLast('.') == REMEMBERED_EFFECT_NAME && text != REMEMBERED_EFFECT_FQN
      }
    ) {
      return null
    }

    val rename = LintFix.create()
      .replace()
      .text(LAUNCHED_EFFECT_NAME)
      .with(REMEMBERED_EFFECT_NAME)
      .build()

    if (references.any { (_, text) -> text == REMEMBERED_EFFECT_FQN }) {
      return LintFix.create().name(FIX_NAME).composite(rename)
    }

    return LintFix.create().name(FIX_NAME).composite(rename, context.addImportFix(references))
  }

  /**
   * The import is written as a plain text edit rather than through `LintFix.imports()`, which
   * lint's own test harness renders but the command line fix applier silently drops, leaving a
   * renamed call with no import behind. Insertion keeps a sorted import block sorted so the fix
   * does not hand the user a formatting violation in exchange.
   */
  private fun JavaContext.addImportFix(references: List<Pair<UImportStatement, String>>): LintFix {
    val line = "import $REMEMBERED_EFFECT_FQN"
    val successor = references.firstOrNull { (_, text) -> text > REMEMBERED_EFFECT_FQN }
    return if (successor != null) {
      LintFix.create().replace().range(getLocation(successor.first)).beginning()
        .with("$line\n").build()
    } else {
      LintFix.create().replace().range(getLocation(references.last().first)).end()
        .with("\n$line").build()
    }
  }

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

      if (resolved.isFunctionTypeInvoke()) {
        // A suspend function type erases to FunctionN and carries its suspend-ness in a
        // Continuation type argument, so invoke's own parameter list says nothing. The type of
        // the invoked value is the only place to read it, and when that is not a function type
        // (an extension-receiver invocation, where UAST hands back the receiver type instead)
        // there is nothing left to read, which is a decline rather than a "does not suspend".
        val invoked = node.receiverType
        when {
          invoked == null || !invoked.isFunctionType() -> declined = true
          invoked.isSuspendFunctionType() -> usesCoroutines = true
        }
        return false
      }

      if (resolved.isCoroutineRelated()) {
        usesCoroutines = true
      }
      return false
    }

    override fun visitThisExpression(node: UThisExpression): Boolean {
      // The block's CoroutineScope receiver escaping into a call or a field is coroutine usage
      // with no call of its own to inspect, and RememberedEffect's block has no receiver to
      // give it. An unknown type is treated as the receiver for the same reason.
      val type = node.getExpressionType()
      if (type == null || InheritanceUtil.isInheritor(type, COROUTINE_SCOPE_FQN)) {
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

    private fun PsiMethod.isFunctionTypeInvoke(): Boolean =
      name == "invoke" && containingClass?.qualifiedName?.startsWith(FUNCTION_TYPE_PREFIX) == true

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

    private fun PsiType.isFunctionType(): Boolean =
      (this as? PsiClassType)?.resolve()?.qualifiedName?.startsWith(FUNCTION_TYPE_PREFIX) == true

    private fun PsiType.isSuspendFunctionType(): Boolean {
      val classType = this as? PsiClassType ?: return false
      return classType.parameters.any {
        InheritanceUtil.isInheritor(it.withoutWildcard(), CONTINUATION_FQN)
      }
    }

    private fun PsiType.withoutWildcard(): PsiType = (this as? PsiWildcardType)?.bound ?: this
  }

  public companion object {

    private const val LAUNCHED_EFFECT_NAME = "LaunchedEffect"
    private const val REMEMBERED_EFFECT_NAME = "RememberedEffect"
    private const val FIX_NAME = "Replace with `RememberedEffect`"
    private const val BLOCK_PARAMETER_NAME = "block"
    private const val COMPOSE_RUNTIME_PACKAGE = "androidx.compose.runtime"
    private const val COROUTINES_PACKAGE = "kotlinx.coroutines"
    private const val COROUTINE_SCOPE_FQN = "kotlinx.coroutines.CoroutineScope"
    private const val CONTINUATION_FQN = "kotlin.coroutines.Continuation"
    private const val FUNCTION_TYPE_PREFIX = "kotlin.jvm.functions.Function"
    private const val REMEMBERED_EFFECT_FQN = "com.skydoves.compose.effects.RememberedEffect"

    private fun PsiMember.packageName(): String? = (containingFile as? PsiClassOwner)?.packageName

    @JvmField
    public val ISSUE: Issue = Issue.create(
      id = "LaunchedEffectWithoutSuspend",
      briefDescription = "LaunchedEffect block does not need a coroutine",
      explanation = "`LaunchedEffect` starts a coroutine on every key change, even when the " +
        "block neither suspends nor uses its `CoroutineScope` receiver. `RememberedEffect` " +
        "runs the block from `RememberObserver.onRemembered()`, which the Compose runtime " +
        "invokes in the apply phase, so it keeps the same guarantee of only running after a " +
        "successful composition while skipping the coroutine allocation.\n" +
        "\n" +
        "The two are not interchangeable. A `RememberedEffect` block runs synchronously on " +
        "the thread applying the composition and cannot be cancelled when its keys change, so " +
        "it suits short, non-blocking work. A block that sleeps, blocks on I/O or loops " +
        "forever belongs in `LaunchedEffect`, and this check cannot tell those apart from a " +
        "cheap one, so treat the suggestion as a question rather than an instruction.",
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
