package com.github.se.pooltrack.model.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GithubReleaseTest {

  private val prefix = "https://github.com/SamiPr0/PoolTrack/releases/download/"

  private val apk =
      GithubAsset(
          name = "PoolTrack-1.2.0.apk",
          downloadUrl = "${prefix}v1.2.0/PoolTrack-1.2.0.apk",
          size = 1234,
          digest = "sha256:ABCDEF",
      )

  private val release = GithubRelease(tagName = "v1.2.0", body = "  Notes\n", assets = listOf(apk))

  @Test
  fun toAppUpdate_mapsEveryField() {
    assertEquals(
        AppUpdate(
            version = "1.2.0",
            notes = "Notes",
            apkUrl = apk.downloadUrl,
            sizeBytes = 1234,
            sha256 = "abcdef",
        ),
        release.toAppUpdate(prefix),
    )
  }

  @Test
  fun toAppUpdate_isNull_whenTheDigestIsMissingOrNotSha256() {
    assertNull(release.copy(assets = listOf(apk.copy(digest = null))).toAppUpdate(prefix))
    assertNull(release.copy(assets = listOf(apk.copy(digest = "md5:abc"))).toAppUpdate(prefix))
  }

  @Test
  fun toAppUpdate_hasEmptyNotes_whenTheReleaseHasNoBody() {
    assertEquals("", release.copy(body = null).toAppUpdate(prefix)?.notes)
  }

  @Test
  fun toAppUpdate_picksTheApkAmongOtherAssets() {
    val other = GithubAsset("mapping.txt", "${prefix}v1.2.0/mapping.txt")

    val update = release.copy(assets = listOf(other, apk)).toAppUpdate(prefix)

    assertEquals(apk.downloadUrl, update?.apkUrl)
  }

  @Test
  fun toAppUpdate_isNull_forDraftsAndPreReleases() {
    assertNull(release.copy(draft = true).toAppUpdate(prefix))
    assertNull(release.copy(prerelease = true).toAppUpdate(prefix))
  }

  @Test
  fun toAppUpdate_isNull_whenThereIsNoApkAsset() {
    assertNull(release.copy(assets = emptyList()).toAppUpdate(prefix))
    assertNull(
        release
            .copy(assets = listOf(GithubAsset("notes.txt", "${prefix}v1.2.0/notes.txt")))
            .toAppUpdate(prefix)
    )
  }

  @Test
  fun toAppUpdate_isNull_whenTheApkIsHostedElsewhere() {
    val foreign = apk.copy(downloadUrl = "https://evil.example.com/PoolTrack.apk")

    assertNull(release.copy(assets = listOf(foreign)).toAppUpdate(prefix))
  }

  @Test
  fun toAppUpdate_isNull_whenTheApkUrlEscapesTheTrustedPrefix() {
    val escaping =
        listOf(
            "${prefix}../../../attacker/repo/releases/download/v9/evil.apk",
            "${prefix}v1.2.0/../../../../attacker/evil.apk",
            "${prefix}./v1.2.0/PoolTrack.apk",
            "${prefix}%2e%2e/%2E%2E/attacker/evil.apk",
            "${prefix}v1.2.0%2f..%2fevil.apk",
            "${prefix}v1.2.0\\..\\evil.apk",
            "${prefix}v1.2.0/PoolTrack.apk?redirect=https://evil.example.com",
            "${prefix}v1.2.0/PoolTrack.apk#../../evil",
        )

    for (url in escaping) {
      val asset = apk.copy(downloadUrl = url)
      assertNull(url, release.copy(assets = listOf(asset)).toAppUpdate(prefix))
    }
  }

  @Test
  fun isTrustedDownloadUrl_acceptsARegularReleaseAsset() {
    assertTrue(isTrustedDownloadUrl("${prefix}v1.2.0/PoolTrack-1.2.0.apk", prefix))
  }

  @Test
  fun isTrustedDownloadUrl_rejectsAnotherRepositoryWithTheSamePrefixText() {
    // "PoolTrack-fork" starts with "PoolTrack", so the prefix must end at a path boundary.
    assertFalse(
        isTrustedDownloadUrl(
            "https://github.com/SamiPr0/PoolTrack-fork/releases/download/v1/evil.apk",
            prefix,
        )
    )
  }

  @Test
  fun release_isDecodedFromGithubJson_ignoringUnknownFields() {
    val text =
        """
        {"tag_name":"v1.2.0","name":"Release","draft":false,"prerelease":false,"body":"Hi",
         "assets":[{"name":"a.apk","browser_download_url":"${prefix}v1.2.0/a.apk","size":5,
         "digest":"sha256:ff","download_count":3}]}
        """
            .trimIndent()

    val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<GithubRelease>(text)

    assertEquals("v1.2.0", decoded.tagName)
    assertEquals("Hi", decoded.body)
    assertEquals(
        GithubAsset("a.apk", "${prefix}v1.2.0/a.apk", 5, "sha256:ff"),
        decoded.assets.single(),
    )
  }
}
