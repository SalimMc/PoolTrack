package com.github.se.pooltrack.model.subscription

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.se.pooltrack.model.backup.deleteFromFirestore
import com.github.se.pooltrack.model.backup.fetchFromFirestore
import com.github.se.pooltrack.model.backup.mirrorToFirestore
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.subscriptionDataStore by preferencesDataStore(name = "subscription_prefs")

// Each account's subscriptions live under their own keys, so one account never sees another's.
private fun subscriptionsKey(uid: String) = stringPreferencesKey("subscriptions_json_$uid")

private fun activeIdKey(uid: String) = stringPreferencesKey("active_subscription_id_$uid")

// Subscriptions added before they were scoped to an account. The first account to sign in
// afterwards claims them (see claimLegacySubscriptions), since it's the device's owner.
private val LEGACY_SUBSCRIPTIONS_KEY = stringPreferencesKey("subscriptions_json")
private val LEGACY_ACTIVE_SUBSCRIPTION_ID_KEY = stringPreferencesKey("active_subscription_id")

// The pass PDF's `uri` is deliberately never mirrored: it's a content:// URI from this device's
// document picker, meaningless on any other device that might read this backup.
private const val BACKUP_COLLECTION = "subscriptions"

/**
 * Stores the user's subscriptions on-device, using Jetpack DataStore, separately for each account.
 *
 * @param currentUid Emits the signed-in account's uid, or `null` when signed out. Reads only ever
 *   see that account's subscriptions (none when signed out), and writes require someone to be
 *   signed in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRepositoryLocal(
    private val context: Context,
    private val currentUid: Flow<String?>,
) : SubscriptionRepository {

  /** Emits the signed-in account's stored data, or `null` when signed out. */
  private val accountPrefs: Flow<Pair<String, Preferences>?> =
      currentUid.distinctUntilChanged().flatMapLatest { uid ->
        if (uid == null) {
          flowOf(null)
        } else {
          channelFlow {
            context.subscriptionDataStore.edit { claimLegacySubscriptions(it, uid) }
            // A fresh install has no subscriptions stored for this account at all: bring back the
            // backup in the background, the stored data then re-emits.
            if (context.subscriptionDataStore.data.first()[subscriptionsKey(uid)] == null) {
              launch { restoreSubscriptions(uid) }
            }
            context.subscriptionDataStore.data.map { uid to it }.collect { send(it) }
          }
        }
      }

  override fun getSubscriptions(): Flow<List<Subscription>> = accountPrefs.map { account ->
    val (uid, prefs) = account ?: return@map emptyList()
    readSubscriptions(prefs, uid).sortedByDescending { it.addedAtEpochMilli }
  }

  override fun getActiveSubscription(): Flow<Subscription?> = accountPrefs.map { account ->
    val (uid, prefs) = account ?: return@map null
    readSubscriptions(prefs, uid).find { it.id == prefs[activeIdKey(uid)] }
  }

  override suspend fun addSubscription(
      uri: String,
      expiresAtEpochMilli: Long?,
      maxEntries: Int?,
      price: Double?,
  ): Subscription {
    val uid = requireUid()
    val contentUri = Uri.parse(uri)
    // Take a long-lived read permission, so the PDF can still be opened after a restart.
    context.contentResolver.takePersistableUriPermission(
        contentUri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
    )
    val subscription =
        Subscription(
            id = UUID.randomUUID().toString(),
            uri = uri,
            displayName = queryDisplayName(contentUri),
            addedAtEpochMilli = System.currentTimeMillis(),
            expiresAtEpochMilli = expiresAtEpochMilli,
            maxEntries = maxEntries,
            price = price,
        )
    context.subscriptionDataStore.edit { prefs ->
      claimLegacySubscriptions(prefs, uid)
      prefs[subscriptionsKey(uid)] =
          Json.encodeToString(readSubscriptions(prefs, uid) + subscription)
      prefs[activeIdKey(uid)] = subscription.id
    }
    mirrorSubscription(subscription)
    return subscription
  }

  override suspend fun updateSubscription(
      id: String,
      displayName: String,
      expiresAtEpochMilli: Long?,
      maxEntries: Int?,
      price: Double?,
  ) {
    val uid = requireUid()
    var updated: Subscription? = null
    context.subscriptionDataStore.edit { prefs ->
      claimLegacySubscriptions(prefs, uid)
      prefs[subscriptionsKey(uid)] =
          Json.encodeToString(
              readSubscriptions(prefs, uid).map { existing ->
                if (existing.id != id) {
                  existing
                } else {
                  existing
                      .copy(
                          displayName = displayName,
                          expiresAtEpochMilli = expiresAtEpochMilli,
                          maxEntries = maxEntries,
                          price = price,
                      )
                      .also { updated = it }
                }
              }
          )
    }
    updated?.let { mirrorSubscription(it) }
  }

  private suspend fun mirrorSubscription(subscription: Subscription) {
    mirrorToFirestore(
        collection = BACKUP_COLLECTION,
        docId = subscription.id,
        data =
            mapOf(
                "id" to subscription.id,
                "displayName" to subscription.displayName,
                "addedAtEpochMilli" to subscription.addedAtEpochMilli,
                "expiresAtEpochMilli" to subscription.expiresAtEpochMilli,
                "maxEntries" to subscription.maxEntries,
                "price" to subscription.price,
            ),
    )
  }

  override suspend fun setActiveSubscription(id: String) {
    val uid = requireUid()
    context.subscriptionDataStore.edit { prefs ->
      claimLegacySubscriptions(prefs, uid)
      prefs[activeIdKey(uid)] = id
    }
  }

  override suspend fun deleteSubscription(id: String) {
    val uid = requireUid()
    context.subscriptionDataStore.edit { prefs ->
      claimLegacySubscriptions(prefs, uid)
      prefs[subscriptionsKey(uid)] =
          Json.encodeToString(readSubscriptions(prefs, uid).filter { it.id != id })
      if (prefs[activeIdKey(uid)] == id) {
        prefs.remove(activeIdKey(uid))
      }
    }
    deleteFromFirestore(collection = BACKUP_COLLECTION, docId = id)
  }

  /**
   * Merges the subscriptions backed up in Firestore into the stored ones, skipping any already
   * there. They come back without their PDF (see [subscriptionFromBackup]), and the newest one that
   * has not expired becomes active if none is.
   */
  private suspend fun restoreSubscriptions(uid: String) {
    val restored = fetchFromFirestore(BACKUP_COLLECTION)?.mapNotNull(::subscriptionFromBackup)
    if (restored.isNullOrEmpty()) return
    context.subscriptionDataStore.edit { prefs ->
      val existing = readSubscriptions(prefs, uid)
      val all = existing + restored.filter { r -> existing.none { it.id == r.id } }
      prefs[subscriptionsKey(uid)] = Json.encodeToString(all)
      if (prefs[activeIdKey(uid)] == null) {
        all.filterNot { it.isExpired }
            .maxByOrNull { it.addedAtEpochMilli }
            ?.let { prefs[activeIdKey(uid)] = it.id }
      }
    }
  }

  private suspend fun requireUid(): String =
      checkNotNull(currentUid.first()) { "Subscriptions can only be changed while signed in" }

  private fun readSubscriptions(prefs: Preferences, uid: String): List<Subscription> =
      prefs[subscriptionsKey(uid)]?.let { Json.decodeFromString<List<Subscription>>(it) }
          ?: emptyList()

  /** Moves any subscriptions stored before they were scoped to an account over to [uid]'s. */
  private fun claimLegacySubscriptions(prefs: MutablePreferences, uid: String) {
    val legacy = prefs[LEGACY_SUBSCRIPTIONS_KEY]
    val legacyActiveId = prefs[LEGACY_ACTIVE_SUBSCRIPTION_ID_KEY]
    if (legacy == null && legacyActiveId == null) return
    if (prefs[subscriptionsKey(uid)] == null) {
      legacy?.let { prefs[subscriptionsKey(uid)] = it }
      legacyActiveId?.let { prefs[activeIdKey(uid)] = it }
    }
    prefs.remove(LEGACY_SUBSCRIPTIONS_KEY)
    prefs.remove(LEGACY_ACTIVE_SUBSCRIPTION_ID_KEY)
  }

  /** Looks up [uri]'s display name via the content provider, falling back to a generic label. */
  private fun queryDisplayName(uri: Uri): String {
    context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (nameIndex >= 0 && cursor.moveToFirst()) {
            cursor.getString(nameIndex)?.let {
              return it
            }
          }
        }
    return "Subscription"
  }
}
