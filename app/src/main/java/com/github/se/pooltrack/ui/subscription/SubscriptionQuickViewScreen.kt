package com.github.se.pooltrack.ui.subscription

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.pooltrack.SecureWindow
import com.github.se.pooltrack.ui.navigation.NavigationActions
import com.github.se.pooltrack.ui.navigation.Screen
import com.github.se.pooltrack.ui.navigation.TopNavigationMenu
import com.github.se.pooltrack.ui.theme.Ripples
import java.time.Duration

object SubscriptionQuickViewScreenTestTags {
  const val LOADING_INDICATOR = "SubscriptionQuickViewScreenLoadingIndicator"
  const val PDF_IMAGE = "SubscriptionQuickViewScreenPdfImage"
  const val ACCEPT_BUTTON = "SubscriptionQuickViewScreenAcceptButton"
  const val COOLDOWN_MESSAGE = "SubscriptionQuickViewScreenCooldownMessage"
  const val CONFIRM_SHEET = "SubscriptionQuickViewScreenConfirmSheet"
}

/**
 * The focused flow opened from Home's FAB: shows the active pass, an Accept button, and a way
 * back - nothing else. Managing subscriptions (adding, switching, deleting) is reserved for the
 * [SubscriptionScreen] tab, reached from the bottom bar.
 */
@Composable
fun SubscriptionQuickViewScreen(
    viewModel: SubscriptionViewModel = viewModel(),
    navigationActions: NavigationActions? = null,
) {
  val activeSubscription by viewModel.activeSubscription.collectAsState()
  val lastEntryTimestamp by viewModel.lastEntryTimestamp.collectAsState()
  val remainingCooldown = rememberRemainingCooldown(lastEntryTimestamp)

  SecureWindow()
  if (remainingCooldown == null && activeSubscription != null) {
    MaxBrightness()
  }

  Scaffold(
      topBar = {
        TopNavigationMenu(
            Screen.SubscriptionQuickView,
            onGoBack = { navigationActions?.goBack() },
        )
      },
  ) { paddingValues ->
    val cooldown = remainingCooldown
    val currentUri = activeSubscription?.uri
    when {
      cooldown != null ->
          CooldownLockedMessage(
              cooldown = cooldown,
              modifier = Modifier.padding(paddingValues),
          )
      currentUri != null && currentUri.isBlank() ->
          PdfMissingMessage(modifier = Modifier.padding(paddingValues))
      currentUri == null -> NoActiveSubscriptionMessage(Modifier.padding(paddingValues))
      else ->
          PassDisplayAndAccept(
              uri = currentUri,
              onAccepted = {
                viewModel.onScannerAccepted()
                navigationActions?.goBack()
              },
              modifier = Modifier.padding(paddingValues),
          )
    }
  }
}

/** Shown instead of the pass when no subscription is active. */
@Composable
private fun NoActiveSubscriptionMessage(modifier: Modifier = Modifier) {
  Column(
      modifier = modifier.fillMaxSize().padding(32.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
        imageVector = Icons.Filled.Person,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "No active subscription",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    Text(
        text = "Add or activate one from the Subscriptions tab.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** Shown instead of the pass while its reopen cooldown is active. */
@Composable
private fun CooldownLockedMessage(cooldown: Duration, modifier: Modifier = Modifier) {
  Column(
      modifier = modifier.fillMaxSize().padding(32.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Icon(
        imageVector = Icons.Filled.Lock,
        contentDescription = null,
        modifier = Modifier.size(64.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "Already entered the pool",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
    Text(
        text = "You can show your pass again in ${formatCooldown(cooldown)}.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(SubscriptionQuickViewScreenTestTags.COOLDOWN_MESSAGE),
    )
  }
}

/**
 * Renders the pass plus the Accept button; calls [onAccepted] once tapped. The user pinch-zooms and
 * pans to bring the QR code up to size themselves - rather than the app guessing where it is on the
 * page - since that guess depends on on-device detection that isn't always available.
 */
@Composable
private fun PassDisplayAndAccept(
    uri: String,
    onAccepted: () -> Unit,
    modifier: Modifier = Modifier,
    zoomViewModel: PassZoomViewModel =
        // Activity-scoped so the zoom outlives this navigation entry.
        viewModel(
            viewModelStoreOwner =
                (LocalActivity.current as? ComponentActivity)
                    ?: checkNotNull(LocalViewModelStoreOwner.current),
        ),
) {
  val pageBitmap by rememberPassFirstPage(uri)
  val transform by zoomViewModel.transform.collectAsState()
  var viewport by remember { mutableStateOf(Size.Zero) }
  SideEffect { zoomViewModel.onPassShown(uri) }
  // Don't leave the last gesture waiting on the save delay when the screen goes away.
  DisposableEffect(zoomViewModel) { onDispose { zoomViewModel.persistNow() } }

  Column(modifier = modifier.fillMaxSize()) {
    Text(
        text = "Pinch to zoom in on the QR code so it fills the screen for the scanner.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
    )
    Box(
        modifier =
            Modifier.weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .onSizeChanged {
                  viewport = Size(it.width.toFloat(), it.height.toFloat())
                  zoomViewModel.onViewportChanged(viewport)
                }
                .pointerInput(Unit) {
                  detectTransformGestures { centroid, pan, zoom, _ ->
                    val center = Offset(size.width / 2f, size.height / 2f)
                    zoomViewModel.onGesture(zoom, centroid - center, pan, viewport)
                  }
                },
        contentAlignment = Alignment.Center,
    ) {
      val bitmap = pageBitmap
      if (bitmap == null) {
        CircularProgressIndicator(
            modifier = Modifier.testTag(SubscriptionQuickViewScreenTestTags.LOADING_INDICATOR),
        )
      } else {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Subscription pass - pinch to zoom in on its QR code",
            contentScale = ContentScale.Fit,
            modifier =
                Modifier.fillMaxSize()
                    .padding(24.dp)
                    .graphicsLayer(
                        scaleX = transform.scale,
                        scaleY = transform.scale,
                        translationX = transform.offset.x,
                        translationY = transform.offset.y,
                    )
                    .testTag(SubscriptionQuickViewScreenTestTags.PDF_IMAGE),
        )
      }
    }

    // A scan the scanner declines must not count as an entry. There's no dedicated
    // "Declined" control for that: simply not tapping Accepted already leaves nothing
    // recorded, and the pass stays up for the user to try scanning again.
    ConfirmScanSheet(onAccepted = onAccepted)
  }
}

/**
 * The bottom sheet under the pass asking whether the gate let the user in: ripples on the water,
 * the question, how a declined scan is handled, and the Accepted button.
 */
@Composable
private fun ConfirmScanSheet(onAccepted: () -> Unit) {
  Surface(
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
      modifier = Modifier.fillMaxWidth().testTag(SubscriptionQuickViewScreenTestTags.CONFIRM_SHEET),
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Ripples(
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(56.dp),
          spread = true,
      )
      Text(
          text = "Did the gate let you in?",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(top = 8.dp),
      )
      Text(
          text = "Declined? Keep the pass up and scan again.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
      )
      Button(
          onClick = onAccepted,
          contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
          modifier =
              Modifier.fillMaxWidth().testTag(SubscriptionQuickViewScreenTestTags.ACCEPT_BUTTON),
      ) {
        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Accepted")
      }
    }
  }
}

/** Forces the current activity's screen brightness to maximum while this composable is shown. */
@Composable
private fun MaxBrightness() {
  val activity = LocalActivity.current ?: return
  DisposableEffect(activity) {
    val window = activity.window
    val originalBrightness = window.attributes.screenBrightness

    val maxParams = window.attributes
    maxParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
    window.attributes = maxParams

    onDispose {
      val restoredParams = window.attributes
      restoredParams.screenBrightness = originalBrightness
      window.attributes = restoredParams
    }
  }
}

/** Shown instead of the pass when the active subscription came back from the backup without it. */
@Composable
private fun PdfMissingMessage(modifier: Modifier = Modifier) {
  Column(
      modifier = modifier.fillMaxSize().padding(32.dp),
      verticalArrangement = Arrangement.Center,
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
        text = "Pass PDF missing",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        text =
            "This subscription was restored from your backup without its PDF. " +
                "Add it from the Subscriptions tab.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
  }
}
