package com.github.se.pooltrack.model.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val SHA256_PREFIX = "sha256:"

/** The subset of GitHub's "get the latest release" response that the updater needs. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

/** A file attached to a [GithubRelease]. [digest] looks like `sha256:<hex>` when GitHub has one. */
@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
    val digest: String? = null,
)

/**
 * Turns this release into an [AppUpdate], or `null` if it cannot be installed: a draft or
 * pre-release, no `.apk` asset, an APK that is not hosted under [trustedUrlPrefix], or an APK
 * GitHub published no SHA-256 for. The prefix check keeps the app from downloading a file from an
 * unexpected host or repository, and the checksum is what lets the download be verified, so an
 * update that cannot be verified is not offered at all.
 */
fun GithubRelease.toAppUpdate(trustedUrlPrefix: String): AppUpdate? {
  if (draft || prerelease) return null
  val apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
  if (!isTrustedDownloadUrl(apk.downloadUrl, trustedUrlPrefix)) return null
  val sha256 =
      apk.digest?.takeIf { it.startsWith(SHA256_PREFIX) }?.removePrefix(SHA256_PREFIX)?.lowercase()
          ?: return null
  return AppUpdate(
      version = tagName.trim().removePrefix("v"),
      notes = body.orEmpty().trim(),
      apkUrl = apk.downloadUrl,
      sizeBytes = apk.size,
      sha256 = sha256,
  )
}

// Characters a server may decode or resolve into a path outside the prefix: encoded dots, slashes
// and backslashes, plain backslashes, and a query or fragment that could hide them.
private val UNSAFE_URL_PARTS = listOf("%2e", "%2f", "%5c", "\\", "?", "#")

/**
 * Whether [url] points under [trustedUrlPrefix] and stays there. A plain prefix check is not
 * enough: `<prefix>../../other/repo/...` starts with the prefix but resolves to another repository.
 */
internal fun isTrustedDownloadUrl(url: String, trustedUrlPrefix: String): Boolean {
  if (!url.startsWith(trustedUrlPrefix)) return false
  val rest = url.substring(trustedUrlPrefix.length)
  if (rest.split('/').any { it == "." || it == ".." }) return false
  val lowercase = rest.lowercase()
  return UNSAFE_URL_PARTS.none { it in lowercase }
}
