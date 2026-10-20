package com.github.se.pooltrack.ui.history

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.platform.app.InstrumentationRegistry
import com.github.se.pooltrack.model.entry.EntryRepositoryLocal
import com.github.se.pooltrack.model.subscription.SubscriptionRepositoryLocal
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HistoryScreenTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val application =
      InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

  @Before
  fun clearStoredEntries() {
    application.preferencesDataStoreFile("entry_prefs").delete()
  }

  @Test
  fun historyScreen_showsEmptyMessage_whenNoEntriesRecorded() {
    show()

    composeTestRule.onNodeWithTag(HistoryScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()
  }

  @Test
  fun historyScreen_opensTheAddSheet_whenTheAddButtonIsTapped() {
    show()

    composeTestRule.onNodeWithTag(HistoryScreenTestTags.ADD_PAST_ENTRY_BUTTON).performClick()

    composeTestRule.onNodeWithTag(AddPastEntrySheetTestTags.SHEET).assertIsDisplayed()
  }

  private fun show() {
    val uid = flowOf("test-user")
    val viewModel =
        HistoryViewModel(
            EntryRepositoryLocal(application, currentUid = uid),
            SubscriptionRepositoryLocal(application, currentUid = uid),
        )
    composeTestRule.setContent { HistoryScreen(viewModel = viewModel) }
  }
}
