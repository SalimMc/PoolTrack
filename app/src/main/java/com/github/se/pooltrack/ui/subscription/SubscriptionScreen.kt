package com.github.se.pooltrack.ui.subscription

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.pooltrack.model.subscription.Subscription
import com.github.se.pooltrack.model.subscription.addedAt
import com.github.se.pooltrack.model.subscription.expiresAt
import com.github.se.pooltrack.model.subscription.hasPdf
import com.github.se.pooltrack.model.subscription.isExpired
import com.github.se.pooltrack.model.subscription.pricePerEntry
import com.github.se.pooltrack.model.subscription.renderFirstPdfPage
import com.github.se.pooltrack.ui.navigation.BottomNavigationMenu
import com.github.se.pooltrack.ui.navigation.NavigationActions
import com.github.se.pooltrack.ui.navigation.Screen
import com.github.se.pooltrack.ui.navigation.Tab
import com.github.se.pooltrack.ui.navigation.TopNavigationMenu
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SubscriptionScreenTestTags {
  const val EMPTY_MESSAGE = "SubscriptionScreenEmptyMessage"
  const val EMPTY_ADD_BUTTON = "SubscriptionScreenEmptyAddButton"
  const val SUBSCRIPTION_LIST = "SubscriptionScreenList"
  const val SUBSCRIPTION_ITEM = "SubscriptionScreenItem"
  const val THUMBNAIL = "SubscriptionScreenThumbnail"
  const val ACTIVE_BADGE = "SubscriptionScreenActiveBadge"
  const val SET_ACTIVE_BUTTON = "SubscriptionScreenSetActiveButton"
  const val EDIT_BUTTON = "SubscriptionScreenEditButton"
  const val ATTACH_PDF_BUTTON = "SubscriptionScreenAttachPdfButton"
  const val EDIT_NAME_FIELD = "SubscriptionScreenEditNameField"
  const val DELETE_BUTTON = "SubscriptionScreenDeleteButton"
  const val DELETE_ENTRIES_NOTE = "SubscriptionScreenDeleteEntriesNote"
  const val CONFIRM_DELETE_BUTTON = "SubscriptionScreenConfirmDeleteButton"
  const val CANCEL_DELETE_BUTTON = "SubscriptionScreenCancelDeleteButton"
  const val ADD_BUTTON = "SubscriptionScreenAddButton"
  const val DETAIL_DIALOG = "SubscriptionScreenDetailDialog"
  const val EXPIRATION_DIALOG = "SubscriptionScreenExpirationDialog"
  const val EXPIRATION_NONE_BUTTON = "SubscriptionScreenExpirationNoneButton"
  const val EXPIRATION_DATE_BUTTON = "SubscriptionScreenExpirationDateButton"
  const val EXPIRATION_ENTRIES_BUTTON = "SubscriptionScreenExpirationEntriesButton"
  const val EXPIRATION_ENTRIES_FIELD = "SubscriptionScreenExpirationEntriesField"
  const val EXPIRATION_PURCHASE_DATE_BUTTON = "SubscriptionScreenExpirationPurchaseDateButton"
  const val EXPIRATION_PRICE_FIELD = "SubscriptionScreenExpirationPriceField"
  const val EXPIRATION_KEEP_BUTTON = "SubscriptionScreenExpirationKeepButton"
  const val EXPIRATION_CONFIRM_BUTTON = "SubscriptionScreenExpirationConfirmButton"
}

// Built fresh on every call rather than cached as a val, so a locale change while the app is
// running (without a process restart) is picked up instead of baked in at class-init time.
private fun addedDateFormatter(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

/** A quick preset offered when picking a date-based expiration - covers the common cases. */
private enum class DatePreset(val label: String, val period: Period) {
  ONE_MONTH("1 month", Period.ofMonths(1)),
  SIX_MONTHS("6 months", Period.ofMonths(6)),
  ONE_YEAR("1 year", Period.ofYears(1)),
}

/** [purchasedOn] plus this preset's period, i.e. the expiration date - not counted from today. */
private fun DatePreset.expiresAtEpochMilli(
    purchasedOn: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Long = purchasedOn.plus(period).atStartOfDay(zone).toInstant().toEpochMilli()

/** Which kind of expiration is being configured in [AddSubscriptionExpirationDialog]. */
private enum class ExpirationMode {
  NONE,
  DATE,
  ENTRIES,
}

/**
 * A short line describing when/how a subscription expires, or `null` if it never does. [usedCount]
 * is how many confirmed entries have been recorded against it, used to turn a fixed entry limit
 * into how many are actually left.
 */
private fun expirationLabel(subscription: Subscription, usedCount: Int): String? {
  subscription.expiresAt?.let { expiresAt ->
    val formatted = addedDateFormatter().format(expiresAt)
    return if (subscription.isExpired) "Expired $formatted" else "Expires $formatted"
  }
  subscription.maxEntries?.let { maxEntries ->
    val remaining = (maxEntries - usedCount).coerceAtLeast(0)
    return "$remaining of $maxEntries ${if (maxEntries == 1) "entry" else "entries"} left"
  }
  return null
}

private fun formatAmount(amount: Double): String =
    String.format(Locale.getDefault(), "%.2f", amount)

/** "Price: 29.90", or `null` if no price was recorded. */
private fun priceLabel(subscription: Subscription): String? =
    subscription.price?.let { "Price: ${formatAmount(it)}" }

/** "0.50 / entry", derived from price and the entry-count limit, or `null` if not computable. */
private fun pricePerEntryLabel(subscription: Subscription): String? =
    subscription.pricePerEntry?.let { "${formatAmount(it)} / entry" }

/** Expiration and price combined onto a single line, for the compact list row. */
private fun rowDetailLine(subscription: Subscription, usedCount: Int): String? =
    listOfNotNull(expirationLabel(subscription, usedCount), priceLabel(subscription))
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" · ")

/**
 * SubscriptionScreen is the always-reachable bottom-nav tab for managing subscriptions: lists every
 * one the user has added - e.g. an expired one kept for reference alongside the new one replacing
 * it. Rows only show the basics (thumbnail, name, date, whether it's active); tapping one opens a
 * detail dialog with the actual actions (set active, delete), so the list itself doesn't repeat the
 * same two buttons on every single row.
 *
 * It deliberately does not display the active subscription's pass itself: that would just duplicate
 * [SubscriptionQuickViewScreen], which Home's FAB already opens for that.
 */
@Composable
fun SubscriptionScreen(
    viewModel: SubscriptionViewModel = viewModel(),
    navigationActions: NavigationActions? = null,
) {
  val subscriptions by viewModel.subscriptions.collectAsState()
  val activeSubscription by viewModel.activeSubscription.collectAsState()
  val entryCountsBySubscriptionId by viewModel.entryCountsBySubscriptionId.collectAsState()
  var selectedSubscription by remember { mutableStateOf<Subscription?>(null) }
  var subscriptionPendingDeletion by remember { mutableStateOf<Subscription?>(null) }
  var subscriptionBeingEdited by remember { mutableStateOf<Subscription?>(null) }
  var pickedPdfUri by remember { mutableStateOf<Uri?>(null) }
  var subscriptionGettingPdf by remember { mutableStateOf<Subscription?>(null) }

  val pickPdfLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
          // Mutually exclusive with the detail dialog below - picking a new PDF while an
          // existing subscription's details are open would otherwise stack both dialogs.
          selectedSubscription = null
          pickedPdfUri = uri
        }
      }

  val attachPdfLauncher =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        subscriptionGettingPdf?.let { target ->
          if (uri != null) viewModel.onAttachPdf(target.id, uri.toString())
        }
        subscriptionGettingPdf = null
      }

  pickedPdfUri?.let { uri ->
    SubscriptionDetailsDialog(
        onConfirm = { _, expiresAtEpochMilli, maxEntries, price ->
          viewModel.onSubscriptionPicked(uri.toString(), expiresAtEpochMilli, maxEntries, price)
          pickedPdfUri = null
        },
        onDismiss = { pickedPdfUri = null },
    )
  }

  subscriptionBeingEdited?.let { subscription ->
    SubscriptionDetailsDialog(
        initial = subscription,
        onConfirm = { name, expiresAtEpochMilli, maxEntries, price ->
          viewModel.onEditSubscription(
              subscription.id,
              name,
              expiresAtEpochMilli,
              maxEntries,
              price,
          )
          subscriptionBeingEdited = null
        },
        onDismiss = { subscriptionBeingEdited = null },
    )
  }

  selectedSubscription?.let { subscription ->
    SubscriptionDetailDialog(
        subscription = subscription,
        isActive = subscription.id == activeSubscription?.id,
        usedCount = entryCountsBySubscriptionId[subscription.id] ?: 0,
        onSetActive = {
          viewModel.onSetActive(subscription.id)
          selectedSubscription = null
        },
        onEdit = {
          selectedSubscription = null
          subscriptionBeingEdited = subscription
        },
        onAttachPdf = {
          selectedSubscription = null
          subscriptionGettingPdf = subscription
          attachPdfLauncher.launch(arrayOf("application/pdf"))
        },
        onDelete = {
          selectedSubscription = null
          subscriptionPendingDeletion = subscription
        },
        onDismiss = { selectedSubscription = null },
    )
  }

  subscriptionPendingDeletion?.let { subscription ->
    AlertDialog(
        onDismissRequest = { subscriptionPendingDeletion = null },
        title = { Text("Delete this subscription?") },
        text = {
          val entryCount = entryCountsBySubscriptionId[subscription.id] ?: 0
          Column {
            Text("\"${subscription.displayName}\" will be permanently removed.")
            if (entryCount > 0) {
              Text(
                  deletionEntriesNote(entryCount),
                  modifier =
                      Modifier.padding(top = 8.dp)
                          .testTag(SubscriptionScreenTestTags.DELETE_ENTRIES_NOTE),
              )
            }
          }
        },
        confirmButton = {
          TextButton(
              onClick = {
                viewModel.onDeleteSubscription(subscription.id)
                subscriptionPendingDeletion = null
              },
              modifier = Modifier.testTag(SubscriptionScreenTestTags.CONFIRM_DELETE_BUTTON),
          ) {
            Text("Delete")
          }
        },
        dismissButton = {
          TextButton(
              onClick = { subscriptionPendingDeletion = null },
              modifier = Modifier.testTag(SubscriptionScreenTestTags.CANCEL_DELETE_BUTTON),
          ) {
            Text("Cancel")
          }
        },
    )
  }

  Scaffold(
      topBar = {
        TopNavigationMenu(
            Screen.Subscription,
            actions = {
              IconButton(
                  onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
                  modifier = Modifier.testTag(SubscriptionScreenTestTags.ADD_BUTTON),
              ) {
                Icon(Icons.Filled.Add, contentDescription = "Add subscription")
              }
            },
        )
      },
      bottomBar = {
        BottomNavigationMenu(
            selectedTab = Tab.Subscription,
            onTabSelected = { tab -> navigationActions?.navigateTo(tab.destination) },
        )
      },
  ) { paddingValues ->
    if (subscriptions.isEmpty()) {
      Column(
          modifier = Modifier.fillMaxSize().padding(paddingValues).padding(32.dp),
          verticalArrangement = Arrangement.Center,
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "No subscriptions yet",
            style = MaterialTheme.typography.titleMedium,
            modifier =
                Modifier.padding(top = 16.dp, bottom = 4.dp)
                    .testTag(SubscriptionScreenTestTags.EMPTY_MESSAGE),
        )
        Text(
            text = "Subscriptions you add will show up here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )
        Button(
            onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
            modifier = Modifier.testTag(SubscriptionScreenTestTags.EMPTY_ADD_BUTTON),
        ) {
          Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(modifier = Modifier.width(8.dp))
          Text("Add subscription PDF")
        }
      }
    } else {
      LazyColumn(
          modifier =
              Modifier.fillMaxSize()
                  .padding(paddingValues)
                  .testTag(SubscriptionScreenTestTags.SUBSCRIPTION_LIST),
          contentPadding = PaddingValues(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(subscriptions, key = { it.id }) { subscription ->
          SubscriptionRow(
              subscription = subscription,
              isActive = subscription.id == activeSubscription?.id,
              usedCount = entryCountsBySubscriptionId[subscription.id] ?: 0,
              onClick = {
                // Mutually exclusive with the add-subscription flow above - picking a new
                // subscription's details while a PDF is still being added would otherwise
                // stack both dialogs.
                pickedPdfUri = null
                selectedSubscription = subscription
              },
          )
        }
      }
    }
  }
}

/** A single row: thumbnail, name, date, and whether it's active - no actions. Tap for those. */
@Composable
private fun SubscriptionRow(
    subscription: Subscription,
    isActive: Boolean,
    usedCount: Int,
    onClick: () -> Unit,
) {
  Card(
      modifier =
          Modifier.fillMaxWidth()
              .clickable(onClick = onClick)
              .testTag(SubscriptionScreenTestTags.SUBSCRIPTION_ITEM),
      shape = RoundedCornerShape(16.dp),
      colors =
          if (isActive) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
          },
      elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 4.dp else 2.dp),
  ) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      if (isActive) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Active",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp).testTag(SubscriptionScreenTestTags.ACTIVE_BADGE),
        )
      } else {
        Spacer(modifier = Modifier.size(28.dp))
      }
      Spacer(modifier = Modifier.width(12.dp))

      SubscriptionThumbnail(uri = subscription.uri, width = 48.dp, height = 64.dp)
      Spacer(modifier = Modifier.width(12.dp))

      Column(modifier = Modifier.weight(1f)) {
        Text(
            text = subscription.displayName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Added ${addedDateFormatter().format(subscription.addedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color =
                if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rowDetailLine(subscription, usedCount)?.let { label ->
          Text(
              text = label,
              style = MaterialTheme.typography.bodySmall,
              color =
                  if (subscription.isExpired) MaterialTheme.colorScheme.error
                  else if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

/** Details for one subscription, plus its "set active" / "edit" / "delete" actions. */
@Composable
private fun SubscriptionDetailDialog(
    subscription: Subscription,
    isActive: Boolean,
    usedCount: Int,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onAttachPdf: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
  Dialog(onDismissRequest = onDismiss) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(SubscriptionScreenTestTags.DETAIL_DIALOG),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
      Column(modifier = Modifier.padding(24.dp)) {
        Row(verticalAlignment = Alignment.Top) {
          Text(
              text = subscription.displayName,
              style = MaterialTheme.typography.titleLarge,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.weight(1f),
          )
          IconButton(
              onClick = onEdit,
              modifier = Modifier.size(32.dp).testTag(SubscriptionScreenTestTags.EDIT_BUTTON),
          ) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit subscription")
          }
          IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
          }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          if (subscription.hasPdf) {
            SubscriptionPreview(uri = subscription.uri, width = 140.dp, height = 184.dp)
          } else {
            // Restored from the backup, which never held the PDF itself.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                  text = "No PDF on this device",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Button(
                  onClick = onAttachPdf,
                  modifier =
                      Modifier.padding(top = 8.dp)
                          .testTag(SubscriptionScreenTestTags.ATTACH_PDF_BUTTON),
              ) {
                Text("Add the PDF")
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Added ${addedDateFormatter().format(subscription.addedAt)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        expirationLabel(subscription, usedCount)?.let { label ->
          Text(
              text = label,
              style = MaterialTheme.typography.bodyMedium,
              color =
                  if (subscription.isExpired) MaterialTheme.colorScheme.error
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth(),
          )
        }

        val priceDetail =
            listOfNotNull(priceLabel(subscription), pricePerEntryLabel(subscription))
                .takeIf { it.isNotEmpty() }
                ?.joinToString("  ·  ")
        priceDetail?.let { label ->
          Text(
              text = label,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
              modifier = Modifier.fillMaxWidth(),
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          StatusChip(isActive = isActive)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          if (!isActive) {
            Button(
                onClick = onSetActive,
                modifier =
                    Modifier.weight(1f).testTag(SubscriptionScreenTestTags.SET_ACTIVE_BUTTON),
            ) {
              Text("Set active")
            }
          }
          OutlinedButton(
              onClick = onDelete,
              colors =
                  ButtonDefaults.outlinedButtonColors(
                      contentColor = MaterialTheme.colorScheme.error,
                  ),
              border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
              modifier = Modifier.weight(1f).testTag(SubscriptionScreenTestTags.DELETE_BUTTON),
          ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Delete")
          }
        }
      }
    }
  }
}

/**
 * Captures a subscription's expiration - a date (via quick presets) or an entry-count limit - and
 * its price. Shown right after picking a PDF, before it's actually added (when [initial] is
 * `null`), and reused to edit an existing subscription, in which case it is pre-filled from
 * [initial] and also offers its name. [onConfirm] receives the edited name (blank when adding).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionDetailsDialog(
    onConfirm: (name: String, expiresAtEpochMilli: Long?, maxEntries: Int?, price: Double?) -> Unit,
    onDismiss: () -> Unit,
    initial: Subscription? = null,
) {
  val isEditing = initial != null
  var nameText by remember { mutableStateOf(initial?.displayName ?: "") }
  var mode by remember {
    mutableStateOf(
        when {
          initial?.expiresAtEpochMilli != null -> ExpirationMode.DATE
          initial?.maxEntries != null -> ExpirationMode.ENTRIES
          else -> ExpirationMode.NONE
        }
    )
  }
  // While editing, no preset picked means "keep the date it already has".
  val keptExpiry = initial?.expiresAtEpochMilli
  var selectedPreset by remember { mutableStateOf<DatePreset?>(null) }
  var purchaseDate by remember { mutableStateOf(LocalDate.now()) }
  var showPurchaseDatePicker by remember { mutableStateOf(false) }
  var entriesText by remember { mutableStateOf(initial?.maxEntries?.toString() ?: "") }
  var priceText by remember {
    mutableStateOf(
        initial?.price?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: ""
    )
  }

  val canConfirm =
      when (mode) {
        ExpirationMode.NONE -> true
        ExpirationMode.DATE -> selectedPreset != null || keptExpiry != null
        ExpirationMode.ENTRIES -> (entriesText.toIntOrNull() ?: 0) > 0
      } && (!isEditing || nameText.isNotBlank())

  AlertDialog(
      onDismissRequest = onDismiss,
      modifier = Modifier.testTag(SubscriptionScreenTestTags.EXPIRATION_DIALOG),
      title = { Text(if (isEditing) "Edit subscription" else "Subscription details") },
      text = {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
          if (isEditing) {
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                label = { Text("Name") },
                singleLine = true,
                modifier =
                    Modifier.fillMaxWidth().testTag(SubscriptionScreenTestTags.EDIT_NAME_FIELD),
            )
            Spacer(modifier = Modifier.height(20.dp))
          }
          Text(
              text = "When does it expire? (optional)",
              style = MaterialTheme.typography.labelLarge,
          )
          Spacer(modifier = Modifier.height(8.dp))
          Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            ChoiceButton(
                label = "No limit",
                selected = mode == ExpirationMode.NONE,
                modifier =
                    Modifier.weight(1f).testTag(SubscriptionScreenTestTags.EXPIRATION_NONE_BUTTON),
                onClick = { mode = ExpirationMode.NONE },
            )
            ChoiceButton(
                label = "By date",
                selected = mode == ExpirationMode.DATE,
                modifier =
                    Modifier.weight(1f).testTag(SubscriptionScreenTestTags.EXPIRATION_DATE_BUTTON),
                onClick = { mode = ExpirationMode.DATE },
            )
            ChoiceButton(
                label = "By entries",
                selected = mode == ExpirationMode.ENTRIES,
                modifier =
                    Modifier.weight(1f)
                        .testTag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_BUTTON),
                onClick = { mode = ExpirationMode.ENTRIES },
            )
          }

          Spacer(modifier = Modifier.height(16.dp))

          when (mode) {
            ExpirationMode.DATE ->
                Column {
                  if (keptExpiry != null) {
                    ChoiceButton(
                        label =
                            "Keep ${addedDateFormatter().format(Instant.ofEpochMilli(keptExpiry))}",
                        selected = selectedPreset == null,
                        modifier =
                            Modifier.fillMaxWidth()
                                .testTag(SubscriptionScreenTestTags.EXPIRATION_KEEP_BUTTON),
                        onClick = { selectedPreset = null },
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                  }
                  Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    DatePreset.entries.forEach { preset ->
                      ChoiceButton(
                          label = preset.label,
                          selected = selectedPreset == preset,
                          modifier = Modifier.weight(1f),
                          onClick = { selectedPreset = preset },
                      )
                    }
                  }

                  Spacer(modifier = Modifier.height(12.dp))

                  val purchaseDateTag = SubscriptionScreenTestTags.EXPIRATION_PURCHASE_DATE_BUTTON
                  OutlinedButton(
                      onClick = { showPurchaseDatePicker = true },
                      modifier = Modifier.fillMaxWidth().testTag(purchaseDateTag),
                  ) {
                    val purchaseInstant =
                        purchaseDate.atStartOfDay(ZoneId.systemDefault()).toInstant()
                    Text("Bought on ${addedDateFormatter().format(purchaseInstant)}")
                  }
                }
            ExpirationMode.ENTRIES ->
                OutlinedTextField(
                    value = entriesText,
                    onValueChange = { entriesText = it.filter(Char::isDigit) },
                    label = { Text("Number of entries") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier =
                        Modifier.fillMaxWidth()
                            .testTag(SubscriptionScreenTestTags.EXPIRATION_ENTRIES_FIELD),
                )
            ExpirationMode.NONE -> {}
          }

          Spacer(modifier = Modifier.height(20.dp))

          Text(
              text = "How much did it cost? (optional)",
              style = MaterialTheme.typography.labelLarge,
          )
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedTextField(
              value = priceText,
              onValueChange = { priceText = it.filter { c -> c.isDigit() || c == '.' } },
              label = { Text("Price") },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
              modifier =
                  Modifier.fillMaxWidth()
                      .testTag(SubscriptionScreenTestTags.EXPIRATION_PRICE_FIELD),
          )
        }
      },
      confirmButton = {
        TextButton(
            onClick = {
              val expiresAtEpochMilli =
                  if (mode == ExpirationMode.DATE) {
                    selectedPreset?.expiresAtEpochMilli(purchasedOn = purchaseDate) ?: keptExpiry
                  } else {
                    null
                  }
              val maxEntries =
                  if (mode == ExpirationMode.ENTRIES) entriesText.toIntOrNull() else null
              onConfirm(nameText, expiresAtEpochMilli, maxEntries, priceText.toDoubleOrNull())
            },
            enabled = canConfirm,
            modifier = Modifier.testTag(SubscriptionScreenTestTags.EXPIRATION_CONFIRM_BUTTON),
        ) {
          Text(if (isEditing) "Save" else "Add")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )

  if (showPurchaseDatePicker) {
    val datePickerState =
        rememberDatePickerState(
            // The picker itself works in UTC, so the selection is anchored there too and
            // converted back the same way, rather than through the device's own zone.
            initialSelectedDateMillis =
                purchaseDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = { showPurchaseDatePicker = false },
        confirmButton = {
          TextButton(
              onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                  purchaseDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                }
                showPurchaseDatePicker = false
              },
          ) {
            Text("OK")
          }
        },
        dismissButton = {
          TextButton(onClick = { showPurchaseDatePicker = false }) { Text("Cancel") }
        },
    ) {
      DatePicker(state = datePickerState)
    }
  }
}

private val CHOICE_BUTTON_PADDING = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

/** A small toggle-style button: filled when [selected], outlined otherwise. */
@Composable
private fun ChoiceButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val text: @Composable RowScope.() -> Unit = {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
  }
  if (selected) {
    Button(
        onClick = onClick,
        contentPadding = CHOICE_BUTTON_PADDING,
        modifier = modifier,
        content = text,
    )
  } else {
    OutlinedButton(
        onClick = onClick,
        contentPadding = CHOICE_BUTTON_PADDING,
        modifier = modifier,
        content = text,
    )
  }
}

@Composable
private fun StatusChip(isActive: Boolean) {
  val containerColor =
      if (isActive) MaterialTheme.colorScheme.primaryContainer
      else MaterialTheme.colorScheme.surfaceVariant
  val contentColor =
      if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
      else MaterialTheme.colorScheme.onSurfaceVariant

  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier =
          Modifier.clip(RoundedCornerShape(50))
              .background(containerColor)
              .padding(horizontal = 14.dp, vertical = 8.dp),
  ) {
    if (isActive) {
      Icon(
          imageVector = Icons.Filled.CheckCircle,
          contentDescription = null,
          tint = contentColor,
          modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.width(6.dp))
    }
    Text(
        text = if (isActive) "Currently active" else "Not active",
        style = MaterialTheme.typography.labelLarge,
        color = contentColor,
    )
  }
}

/** A larger, elevated preview of the PDF's first page, used in the detail dialog. */
@Composable
private fun SubscriptionPreview(uri: String, width: Dp, height: Dp) {
  val context = LocalContext.current
  var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(uri) {
    bitmap = withContext(Dispatchers.IO) { renderFirstPdfPage(context, Uri.parse(uri)) }
  }

  Surface(
      modifier = Modifier.size(width = width, height = height),
      shape = RoundedCornerShape(16.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      shadowElevation = 8.dp,
  ) {
    bitmap?.let {
      Image(
          bitmap = it.asImageBitmap(),
          contentDescription = "Subscription pass preview",
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

/** A preview of the PDF's first page, rendered asynchronously, at the given [width]/[height]. */
@Composable
private fun SubscriptionThumbnail(uri: String, width: Dp, height: Dp) {
  val context = LocalContext.current
  var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

  LaunchedEffect(uri) {
    bitmap = withContext(Dispatchers.IO) { renderFirstPdfPage(context, Uri.parse(uri)) }
  }

  Box(
      modifier =
          Modifier.size(width = width, height = height)
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant)
              .border(
                  BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                  RoundedCornerShape(8.dp),
              )
              .testTag(SubscriptionScreenTestTags.THUMBNAIL),
  ) {
    bitmap?.let {
      Image(
          bitmap = it.asImageBitmap(),
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
      )
    }
  }
}

/** Tells the user that deleting a subscription leaves the entries made with it in the history. */
internal fun deletionEntriesNote(entryCount: Int): String =
    if (entryCount == 1) "Its 1 entry stays in your history."
    else "Its $entryCount entries stay in your history."
