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

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun rememberEffectTest() {
    var effectCounter = 0

    composeTestRule.setContent {
      var counter by remember { mutableStateOf(0) }

      RememberedEffect(key1 = counter) {
        effectCounter = counter
      }

      Button(
        modifier = Modifier.testTag("Button"),
        onClick = { counter++ },
      ) { Text(text = "click") }
    }

    val button = composeTestRule.onNodeWithTag("Button")

    button.assertIsDisplayed()

    assertEquals(effectCounter, 0)

    button.performClick()
    button.performClick()

    composeTestRule.waitForIdle()

    assertEquals(effectCounter, 2)
  }
}
