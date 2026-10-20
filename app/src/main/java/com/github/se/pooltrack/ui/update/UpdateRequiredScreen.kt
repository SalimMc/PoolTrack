package com.github.se.pooltrack.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.se.pooltrack.model.update.AppUpdate
import java.util.Locale

object UpdateRequiredScreenTestTags {
  const val SCREEN = "UpdateRequiredScreen"
  const val TITLE = "UpdateRequiredTitle"
  const val VERSION = "UpdateRequiredVersion"
  const val NOTES = "UpdateRequiredNotes"
  const val PROGRESS = "UpdateRequiredProgress"
  const val MESSAGE = "UpdateRequiredMessage"
  const val CONFIRM_BUTTON = "UpdateRequiredConfirmButton"
  const val CANCEL_BUTTON = "UpdateRequiredCancelButton"
  const val SKIP_BUTTON = "UpdateRequiredSkipButton"
}

private const val BYTES_PER_MB = 1024.0 * 1024.0
private const val PERCENT = 100

/**
 * Shown instead of the whole app while a newer release exists: the user must install it before
 * using PoolTrack. It guides them through the download and the installation, and only after the
 * update failed several times does it offer to continue without it (until the next launch).
 * `PoolTrackApp` decides when to show it.
 */
@Composable
fun UpdateRequiredScreen(viewModel: UpdateViewModel = viewModel()) {
  val state by viewModel.state.collectAsState()
  UpdateRequiredContent(
      state = state,
      onConfirm = {
        if (state is UpdateUiState.ReadyToInstall) viewModel.onInstallClick()
        else viewModel.onUpdateClick()
      },
      onCancelDownload = viewModel::onCancelDownload,
      onSkip = viewModel::onSkipClick,
  )
}

/** The stateless part of [UpdateRequiredScreen]: its content follows [state]. */
@Composable
fun UpdateRequiredContent(
    state: UpdateUiState,
    onConfirm: () -> Unit,
    onCancelDownload: () -> Unit,
    onSkip: () -> Unit = {},
) {
  when (state) {
    UpdateUiState.None -> Unit
    is UpdateUiState.Available ->
        Screen(title = "Update required", confirmText = "Update", onConfirm = onConfirm) {
          Message("Install the new version to keep using PoolTrack.")
          VersionAndNotes(state.update)
        }
    is UpdateUiState.Downloading ->
        Screen(
            title = "Downloading update",
            confirmText = null,
            onConfirm = onConfirm,
            cancelText = "Cancel",
            onCancel = onCancelDownload,
        ) {
          Progress(state.progress)
        }
    is UpdateUiState.ReadyToInstall ->
        Screen(title = "Ready to install", confirmText = "Install", onConfirm = onConfirm) {
          Message(
              if (state.needsPermission) {
                "Allow PoolTrack to install apps in the settings that just opened, then come " +
                    "back here."
              } else {
                "PoolTrack will close to finish the update. Open it again afterwards."
              }
          )
        }
    is UpdateUiState.Failed ->
        Screen(
            title = "Update failed",
            confirmText = "Retry",
            onConfirm = onConfirm,
            cancelText = if (state.canSkip) "Continue without updating" else null,
            onCancel = onSkip,
            cancelTag = UpdateRequiredScreenTestTags.SKIP_BUTTON,
        ) {
          Message(state.message)
          if (state.canSkip) {
            Message("You can use PoolTrack now. The update is offered again next time you open it.")
          }
        }
  }
}

@Composable
private fun Screen(
    title: String,
    confirmText: String?,
    onConfirm: () -> Unit,
    cancelText: String? = null,
    onCancel: () -> Unit = {},
    cancelTag: String = UpdateRequiredScreenTestTags.CANCEL_BUTTON,
    content: @Composable () -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxSize().padding(32.dp).testTag(UpdateRequiredScreenTestTags.SCREEN),
      verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag(UpdateRequiredScreenTestTags.TITLE),
    )
    content()
    if (confirmText != null) {
      Button(
          onClick = onConfirm,
          modifier = Modifier.testTag(UpdateRequiredScreenTestTags.CONFIRM_BUTTON),
      ) {
        Text(confirmText)
      }
    }
    if (cancelText != null) {
      TextButton(
          onClick = onCancel,
          modifier = Modifier.testTag(cancelTag),
      ) {
        Text(cancelText)
      }
    }
  }
}

@Composable
private fun VersionAndNotes(update: AppUpdate) {
  val size = if (update.sizeBytes > 0) " (${formatSize(update.sizeBytes)})" else ""
  Text(
      "Version ${update.version}$size",
      style = MaterialTheme.typography.titleSmall,
      modifier = Modifier.testTag(UpdateRequiredScreenTestTags.VERSION),
  )
  val notes = update.notes.toPlainNotes()
  if (notes.isNotEmpty()) {
    Column(modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
      Text(
          notes,
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.testTag(UpdateRequiredScreenTestTags.NOTES),
      )
    }
  }
}

@Composable
private fun Progress(progress: Float?) {
  val modifier = Modifier.fillMaxWidth().testTag(UpdateRequiredScreenTestTags.PROGRESS)
  if (progress == null) {
    LinearProgressIndicator(modifier = modifier)
    Text("Starting download…")
  } else {
    LinearProgressIndicator(progress = { progress }, modifier = modifier)
    Text("${(progress * PERCENT).toInt()}%")
  }
}

@Composable
private fun Message(text: String) {
  Text(
      text,
      textAlign = TextAlign.Center,
      modifier = Modifier.testTag(UpdateRequiredScreenTestTags.MESSAGE),
  )
}

/** Formats [bytes] in megabytes with one decimal, e.g. `12.3 MB`. */
internal fun formatSize(bytes: Long): String =
    String.format(Locale.getDefault(), "%.1f MB", bytes / BYTES_PER_MB)

/**
 * Turns GitHub's Markdown release notes into plain text the screen can show: headings and bold
 * markers are dropped and list items get a bullet.
 */
internal fun String.toPlainNotes(): String =
    lines()
        .map { line ->
          line
              .trim()
              .replace(Regex("^#{1,6}\\s*"), "")
              .replace(Regex("^[-*]\\s+"), "• ")
              .replace("**", "")
              .replace("__", "")
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
