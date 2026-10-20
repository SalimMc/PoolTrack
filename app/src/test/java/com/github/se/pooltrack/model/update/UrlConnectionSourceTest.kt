package com.github.se.pooltrack.model.update

import java.io.IOException
import java.net.ServerSocket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/** Runs [UrlConnectionSource] against a tiny HTTP server on localhost. */
class UrlConnectionSourceTest {

  private lateinit var server: ServerSocket
  private lateinit var thread: Thread

  private val body = "hello"

  // The test server is plain HTTP on localhost, which the app itself would never accept.
  private val source = UrlConnectionSource { it.host == "localhost" }

  @Before
  fun setUp() {
    server = ServerSocket(0)
    thread = Thread {
      try {
        while (true) serve(server.accept())
      } catch (_: IOException) {
        // The socket was closed by tearDown.
      }
    }
    thread.start()
  }

  @After
  fun tearDown() {
    server.close()
    thread.join()
  }

  private fun serve(socket: java.net.Socket) {
    socket.use {
      val path = it.getInputStream().bufferedReader().readLine().split(' ')[1]
      val response =
          when (path) {
            "/ok" ->
                "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
            "/redirect" ->
                "HTTP/1.1 302 Found\r\nLocation: http://localhost:${server.localPort}/ok\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
            "/to-untrusted" ->
                "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:${server.localPort}/ok\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
            "/loop" ->
                "HTTP/1.1 302 Found\r\nLocation: /loop\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
            "/no-location" -> "HTTP/1.1 302 Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
            else -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
          }
      it.getOutputStream().write(response.toByteArray())
    }
  }

  private fun url(path: String) = "http://localhost:${server.localPort}$path"

  @Test
  fun open_returnsTheBodyAndItsLength() {
    source.open(url("/ok")).use {
      assertEquals(body, it.body.bufferedReader().readText())
      assertEquals(body.length.toLong(), it.contentLength)
    }
  }

  @Test
  fun open_followsRedirects() {
    source.open(url("/redirect")).use { assertEquals(body, it.body.bufferedReader().readText()) }
  }

  @Test
  fun open_throwsIOException_whenTheStatusIsNotSuccessful() {
    val error = assertThrows(IOException::class.java) { source.open(url("/missing")) }

    assertEquals("HTTP 404 for ${url("/missing")}", error.message)
  }

  @Test
  fun open_refusesARedirectToAnUntrustedHost() {
    val error = assertThrows(IOException::class.java) { source.open(url("/to-untrusted")) }

    assertEquals(true, error.message!!.startsWith("Untrusted address"))
  }

  @Test
  fun open_givesUp_afterTooManyRedirects() {
    val error = assertThrows(IOException::class.java) { source.open(url("/loop")) }

    assertEquals(true, error.message!!.startsWith("Too many redirects"))
  }

  @Test
  fun open_fails_whenARedirectHasNoTarget() {
    val error = assertThrows(IOException::class.java) { source.open(url("/no-location")) }

    assertEquals(true, error.message!!.startsWith("Bad redirect"))
  }

  @Test
  fun open_refusesTheFirstAddress_byDefault_unlessItIsGithubOverHttps() {
    val error = assertThrows(IOException::class.java) { UrlConnectionSource().open(url("/ok")) }

    assertEquals(true, error.message!!.startsWith("Untrusted address"))
  }

  @Test
  fun isTrustedGithubUrl_acceptsGithubAndItsFileHosts_overHttps() {
    listOf(
            "https://github.com/SamiPr0/PoolTrack/releases/download/v1/a.apk",
            "https://api.github.com/repos/SamiPr0/PoolTrack/releases/latest",
            "https://objects.githubusercontent.com/x",
            "https://release-assets.githubusercontent.com/x",
        )
        .forEach { assertEquals(it, true, isTrustedGithubUrl(java.net.URL(it))) }
  }

  @Test
  fun isTrustedGithubUrl_refusesOtherHosts_lookalikes_andPlainHttp() {
    listOf(
            "http://github.com/x",
            "https://evil.example.com/github.com",
            "https://github.com.evil.example.com/x",
            "https://notgithub.com/x",
            "https://evilgithubusercontent.com/x",
        )
        .forEach { assertEquals(it, false, isTrustedGithubUrl(java.net.URL(it))) }
  }

  @Test
  fun readTextLimited_returnsTheText_whenItFits() {
    assertEquals("héllo", "héllo".byteInputStream().readTextLimited(100))
  }

  @Test
  fun readTextLimited_fails_whenTheStreamIsLarger() {
    assertThrows(IOException::class.java) { "x".repeat(50).byteInputStream().readTextLimited(10) }
  }
}
