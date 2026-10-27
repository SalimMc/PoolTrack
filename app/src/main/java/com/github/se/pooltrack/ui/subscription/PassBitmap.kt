package com.github.se.pooltrack.ui.subscription

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.github.se.pooltrack.model.subscription.renderPassFirstPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MIME types the pass pickers accept: a PDF, or an image such as a screenshot of a QR code. */
internal val PASS_MIME_TYPES = arrayOf("application/pdf", "image/png", "image/jpeg")

/**
 * Renders the pass (image or first PDF page) at [uri] on [dispatcher], keeping the UI thread free.
 */
internal suspend fun loadPassFirstPage(
    context: Context,
    uri: String,
    dispatcher: CoroutineDispatcher,
    render: (Context, Uri) -> Bitmap? = ::renderPassFirstPage,
): Bitmap? = withContext(dispatcher) { render(context, Uri.parse(uri)) }

/**
 * The pass at [uri] (image or first PDF page), rendered off the main thread; null until it is
 * ready.
 */
@Composable
internal fun rememberPassFirstPage(
    uri: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): State<Bitmap?> {
  val context = LocalContext.current
  val bitmap = remember(uri) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(uri) { bitmap.value = loadPassFirstPage(context, uri, dispatcher) }
  return bitmap
}
