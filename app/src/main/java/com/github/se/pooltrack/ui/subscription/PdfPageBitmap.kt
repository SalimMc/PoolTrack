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
import com.github.se.pooltrack.model.subscription.renderFirstPdfPage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** MIME types the PDF pickers accept. */
internal val PDF_MIME_TYPES = arrayOf("application/pdf")

/** Renders the first page of the PDF at [uri] on [dispatcher], keeping the UI thread free. */
internal suspend fun loadPdfFirstPage(
    context: Context,
    uri: String,
    dispatcher: CoroutineDispatcher,
    render: (Context, Uri) -> Bitmap? = ::renderFirstPdfPage,
): Bitmap? = withContext(dispatcher) { render(context, Uri.parse(uri)) }

/** The first page of the PDF at [uri], rendered off the main thread; null until it is ready. */
@Composable
internal fun rememberPdfFirstPage(
    uri: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): State<Bitmap?> {
  val context = LocalContext.current
  val bitmap = remember(uri) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(uri) { bitmap.value = loadPdfFirstPage(context, uri, dispatcher) }
  return bitmap
}
