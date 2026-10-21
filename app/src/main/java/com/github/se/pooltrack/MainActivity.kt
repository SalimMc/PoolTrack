package com.github.se.pooltrack

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.se.pooltrack.model.entry.EntryRepositoryProvider
import com.github.se.pooltrack.model.migration.migrateLocalDataOfSignedInAccounts
import com.github.se.pooltrack.model.store.FirestoreDocumentStore
import com.github.se.pooltrack.model.subscription.PassZoomRepositoryProvider
import com.github.se.pooltrack.model.subscription.SubscriptionRepositoryProvider
import com.github.se.pooltrack.model.swim.isSwimPending
import com.github.se.pooltrack.model.update.UpdateRepositoryProvider
import com.github.se.pooltrack.model.usage.UsageRepositoryProvider
import com.github.se.pooltrack.swim.InactivityReminderWorker
import com.github.se.pooltrack.swim.SwimNotifications
import com.github.se.pooltrack.swim.SwimTrackingService
import com.github.se.pooltrack.swim.UpdateCheckWorker
import com.github.se.pooltrack.ui.account.AccountViewModel
import com.github.se.pooltrack.ui.account.SignInScreen
import com.github.se.pooltrack.ui.entry.EntryDetailsScreen
import com.github.se.pooltrack.ui.history.HistoryScreen
import com.github.se.pooltrack.ui.home.HomeScreen
import com.github.se.pooltrack.ui.navigation.ENTRY_TIMESTAMP_ARG
import com.github.se.pooltrack.ui.navigation.NavigationActions
import com.github.se.pooltrack.ui.navigation.Screen
import com.github.se.pooltrack.ui.poolstay.PoolStayScreen
import com.github.se.pooltrack.ui.poolstay.PoolStayViewModel
import com.github.se.pooltrack.ui.subscription.SubscriptionQuickViewScreen
import com.github.se.pooltrack.ui.subscription.SubscriptionScreen
import com.github.se.pooltrack.ui.theme.PoolTrackTheme
import com.github.se.pooltrack.ui.update.UpdateRequiredScreen
import com.github.se.pooltrack.ui.update.UpdateUiState
import com.github.se.pooltrack.ui.update.UpdateViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val startDestination = Screen.Home.route

/** `MainActivity` is the entry point of the application. */
class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Debug builds stay screenshot-able, for design reviews and bug reports.
    window.applyScreenSecurity(secure = !BuildConfig.DEBUG)
    FirestoreDocumentStore.enableOfflineCache()
    EntryRepositoryProvider.init(this)
    SubscriptionRepositoryProvider.init(this)
    PassZoomRepositoryProvider.init(this)
    UpdateRepositoryProvider.init(this)
    requestNotificationPermissionIfNeeded()
    startSwimTrackingWhileASwimIsPending()
    InactivityReminderWorker.schedule(this)
    UpdateCheckWorker.schedule(this)
    lifecycleScope.launch { migrateLocalDataOfSignedInAccounts(this@MainActivity) }
    // Only a fresh launch counts as an open, not the activity being recreated by a rotation.
    if (savedInstanceState == null) {
      lifecycleScope.launch {
        UsageRepositoryProvider.repository.recordOpen(BuildConfig.VERSION_NAME)
      }
    }

    setContent { PoolTrackTheme { Surface(modifier = Modifier.fillMaxSize()) { PoolTrackApp() } } }
  }

  // Needed to remind the user to log their distance; without it the swim is tracked but silent.
  private fun requestNotificationPermissionIfNeeded() {
    if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
    ) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
    }
  }

  // Foreground services may only be started while the app is visible, i.e. right after the
  // user confirmed the entry here, so this watches the entries while the activity is started.
  private fun startSwimTrackingWhileASwimIsPending() {
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        EntryRepositoryProvider.repository
            .getEntries()
            .map { isSwimPending(it.firstOrNull()) }
            .distinctUntilChanged()
            .collect { pending ->
              if (pending) SwimTrackingService.start(this@MainActivity)
              // Logged or cancelled in the app: the reminder, if posted, is stale.
              else SwimNotifications.cancelLogReminder(this@MainActivity)
            }
      }
    }
  }
}

/**
 * `PoolTrackApp` is the main composable function that sets up the whole app UI. Gated behind Google
 * sign-in - [SignInScreen] is shown instead until a real (non-anonymous) account is signed in -
 * since subscriptions and entries are only meaningful once backed up to one. A newer release takes
 * precedence over that: [UpdateRequiredScreen] replaces the whole app until it is installed. If the
 * check can't reach GitHub (e.g. offline at the pool), the app stays usable so the pass can still
 * be shown. Once signed in, a stay in the pool replaces the app too ([PoolStayScreen]) until the
 * user logged how far they swam, and that comes even before the update, so a swim that just ended
 * can be logged first. See [appGate] for the exact order.
 */
@Composable
fun PoolTrackApp(
    accountViewModel: AccountViewModel = viewModel(),
    updateViewModel: UpdateViewModel = viewModel(),
) {
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updateViewModel.onResume() }
  val updateState by updateViewModel.state.collectAsState()
  val currentUser by accountViewModel.currentUser.collectAsState()
  val user = currentUser
  // After a confirmed scan the user sees only the pool stay screen, until they logged a distance.
  // It is derived from the stored entry, so reopening the app later lands here again.
  val poolStayViewModel: PoolStayViewModel = viewModel()
  val poolStay by poolStayViewModel.state.collectAsState()

  when (
      appGate(
          updateAvailable = updateState !is UpdateUiState.None,
          signedIn = user != null && !user.isAnonymous,
          poolStay = poolStay,
      )
  ) {
    AppGate.Loading -> return
    AppGate.PoolStay -> {
      PoolStayScreen(poolStayViewModel)
      return
    }
    AppGate.Update -> {
      UpdateRequiredScreen(updateViewModel)
      return
    }
    AppGate.SignIn -> {
      SignInScreen(viewModel = accountViewModel)
      return
    }
    AppGate.App -> Unit
  }

  val navController: NavHostController = rememberNavController()
  val navigationActions = NavigationActions(navController)

  NavHost(navController = navController, startDestination = startDestination) {
    composable(Screen.Home.route) { HomeScreen(navigationActions = navigationActions) }
    composable(Screen.Subscription.route) {
      SubscriptionScreen(navigationActions = navigationActions)
    }
    composable(Screen.SubscriptionQuickView.route) {
      SubscriptionQuickViewScreen(navigationActions = navigationActions)
    }
    composable(Screen.History.route) { HistoryScreen(navigationActions = navigationActions) }
    composable(
        Screen.EntryDetails.route,
        arguments = listOf(navArgument(ENTRY_TIMESTAMP_ARG) { type = NavType.LongType }),
    ) { backStackEntry ->
      EntryDetailsScreen(
          timestampEpochMilli = backStackEntry.arguments?.getLong(ENTRY_TIMESTAMP_ARG) ?: 0L,
          navigationActions = navigationActions,
      )
    }
  }
}
