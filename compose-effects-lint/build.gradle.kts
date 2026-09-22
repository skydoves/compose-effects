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
import com.skydoves.compose.effects.Configuration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  kotlin("jvm")
  id(libs.plugins.nexus.plugin.get().pluginId)
}

apply(from = "${rootDir}/scripts/publish-module.gradle.kts")

mavenPublishing {
  val artifactId = "compose-effects-lint"
  coordinates(
    Configuration.artifactGroup,
    artifactId,
    rootProject.extra.get("libVersion").toString()
  )

  pom {
    name.set(artifactId)
    description.set("Lint checks that point LaunchedEffect usages without coroutine work at RememberedEffect.")
  }
}

kotlin {
  explicitApi()
}

configurations {
  // The lint JAR is loaded by AGP's own lint runtime, which already supplies the lint API.
  // Publishing the API as a transitive dependency would drag the whole toolchain into consumers.
  compileOnly {
    isTransitive = false
  }
}

dependencies {
  compileOnly(libs.lint.api)
  compileOnly(libs.lint.checks)

  testImplementation(libs.lint)
  testImplementation(libs.lint.tests)
  testImplementation(libs.junit)
}

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

tasks.jar {
  manifest {
    attributes("Lint-Registry-v2" to "com.skydoves.compose.effects.lint.ComposeEffectsIssueRegistry")
  }
}
