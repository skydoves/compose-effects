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

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/**
 * Registry for Compose Effects lint rules.
 *
 * Android Studio and AGP discover it through the `Lint-Registry-v2` manifest attribute of the
 * published lint JAR, so consumers get the checks without any setup.
 */
public class ComposeEffectsIssueRegistry : IssueRegistry() {

  override val api: Int = CURRENT_API

  override val minApi: Int = 14 // Lint API version 14 (AGP 8.0+)

  override val issues: List<Issue> = listOf(
    LaunchedEffectWithoutSuspendDetector.ISSUE,
  )

  override val vendor: Vendor = Vendor(
    vendorName = "Compose Effects",
    identifier = "com.github.skydoves.compose.effects.lint",
    feedbackUrl = "https://github.com/skydoves/compose-effects/issues",
    contact = "skydoves2@gmail.com",
  )
}
