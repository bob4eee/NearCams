package com.camradar

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.camradar.ui.PermissionRationale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionRationaleTest {
    @get:Rule val rule = createComposeRule()

    @Test fun explainsForegroundOnlyAndOffersBothChoices() {
        var granted = 0; var skipped = 0
        rule.setContent { PermissionRationale(onGrant = { granted++ }, onSkip = { skipped++ }) }
        rule.onNodeWithText("never in the background", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("grant_location").assertTextContains("while using the app", substring = true).performClick()
        rule.onNodeWithTag("skip_location").performClick()
        assertEquals(1, granted); assertEquals(1, skipped)
    }
}
