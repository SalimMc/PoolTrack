package com.github.se.pooltrack.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** One of the app's top-level, always-reachable destinations. */
sealed class Tab(
    val name: String,
    val icon: ImageVector,
    val destination: Screen,
    val testTag: String,
) {
  object Home : Tab("Home", Icons.Outlined.Home, Screen.Home, NavigationTestTags.HOME_TAB)

  object Subscription :
      Tab(
          "Subscriptions",
          Icons.Outlined.Person,
          Screen.Subscription,
          NavigationTestTags.SUBSCRIPTION_TAB,
      )

  object History :
      Tab(
          "History",
          Icons.AutoMirrored.Outlined.List,
          Screen.History,
          NavigationTestTags.HISTORY_TAB,
      )
}

private val tabs = listOf(Tab.Home, Tab.Subscription, Tab.History)

/** Bottom bar shared by every top-level screen, letting the user switch between them directly. */
@Composable
fun BottomNavigationMenu(
    selectedTab: Tab,
    onTabSelected: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  Column(modifier = modifier.fillMaxWidth()) {
    // A hairline instead of a shadow between the page and the bar.
    HorizontalDivider(thickness = 1.dp, color = scheme.outlineVariant.copy(alpha = 0.5f))
    NavigationBar(
        containerColor = scheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().testTag(NavigationTestTags.BOTTOM_NAVIGATION_MENU),
    ) {
      tabs.forEach { tab ->
        NavigationBarItem(
            icon = { Icon(tab.icon, contentDescription = null) },
            label = { Text(tab.name) },
            selected = tab == selectedTab,
            onClick = { onTabSelected(tab) },
            colors =
                NavigationBarItemDefaults.colors(
                    selectedIconColor = scheme.onPrimary,
                    selectedTextColor = scheme.primary,
                    indicatorColor = scheme.primary,
                ),
            modifier = Modifier.testTag(tab.testTag),
        )
      }
    }
  }
}
