package com.wangzan101.mika.download

import com.wangzan101.mika.about.DownloadPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** 一个任务按顺序下多个文件,每个文件各自换来源、各自校验;不同任务互不影响。 */
class VerifiedDownloadTest {
    private lateinit var server: MockWebServer
    private val dir: File = Files.createTempDirectory("verified").toFile()
    private val http = OkHttpClient()

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown(); dir.deleteRecursively() }

    private fun url(path: String) = server.url(path).toString()
    private fun expect(text: String): (File) -> Unit = { if (it.readText() != text) throw IOException("digest mismatch") }
    private fun file(name: String, text: String, vararg paths: String) =
        DownloadFile(File(dir, name), paths.map(::url), expect(text))

    @Test fun multiFileJobCompletesInOrder() = runTest {
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        val d = VerifiedDownload(http, this)
        var completed = 0
        assertTrue(d.start("pack", listOf(file("a", "one", "/a"), file("b", "two", "/b"))) { completed++ })
        advanceUntilIdle()
        assertEquals(DownloadPhase.Ready, d.progress("pack").value.phase)
        assertEquals(1, completed)
        assertEquals("/a", server.takeRequest().path)
        assertEquals("/b", server.takeRequest().path)
        assertEquals("one", File(dir, "a").readText())
        assertEquals("two", File(dir, "b").readText())
        assertEquals(6L, d.progress("pack").value.doneBytes)
    }

    @Test fun wrongHashFallsToNextSourcePerFile() = runTest {
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("bad"))
        server.enqueue(MockResponse().setBody("two"))
        val d = VerifiedDownload(http, this)
        d.start("pack", listOf(file("a", "one", "/a1", "/a2"), file("b", "two", "/b1", "/b2")))
        advanceUntilIdle()
        assertEquals(DownloadPhase.Ready, d.progress("pack").value.phase)
        assertEquals(listOf("/a1", "/b1", "/b2"), List(3) { server.takeRequest().path })
        assertEquals(3, server.requestCount)
        assertEquals("two", File(dir, "b").readText())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun allSourcesFailFailsJobAndLeavesNoTargets() = runTest {
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("bad"))
        val d = VerifiedDownload(http, this)
        var completed = false
        d.start("pack", listOf(file("a", "one", "/a"), file("b", "two", "/b1", "/b2"))) { completed = true }
        advanceUntilIdle()
        val p = d.progress("pack").value
        assertEquals(DownloadPhase.Failed, p.phase)
        assertNotNull(p.error)
        assertFalse(completed)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun failingOnCompleteFailsTheJob() = runTest {
        server.enqueue(MockResponse().setBody("one"))
        val d = VerifiedDownload(http, this)
        d.start("pack", listOf(file("a", "one", "/a"))) { throw IOException("cannot install") }
        advanceUntilIdle()
        assertEquals(DownloadPhase.Failed, d.progress("pack").value.phase)
    }

    @Test fun independentKeysDoNotCancel() = runTest {
        server.enqueue(MockResponse().setBody("apk"))
        server.enqueue(MockResponse().setBody("model"))
        val d = VerifiedDownload(http, this)
        assertTrue(d.start("app-update", listOf(file("u.apk", "apk", "/apk"))))
        assertTrue(d.start("voice-pack:x", listOf(file("m", "model", "/m"))))
        advanceUntilIdle()
        assertEquals(DownloadPhase.Ready, d.progress("app-update").value.phase)
        assertEquals(DownloadPhase.Ready, d.progress("voice-pack:x").value.phase)

        server.enqueue(MockResponse().setBody("apk2"))
        assertTrue(d.start("app-update", listOf(file("u2.apk", "apk2", "/apk2"))))
        assertTrue(d.start("voice-pack:y", listOf(file("n", "model", "/n"))))
        d.cancel("voice-pack:y")
        advanceUntilIdle()
        assertEquals(DownloadPhase.Ready, d.progress("app-update").value.phase)
        assertEquals("apk2", File(dir, "u2.apk").readText())
        assertEquals(DownloadPhase.Idle, d.progress("voice-pack:y").value.phase)
        assertFalse(File(dir, "n").exists())
    }

    @Test fun sameKeyStartReturnsFalse() = runTest {
        server.enqueue(MockResponse().setBody("one"))
        val d = VerifiedDownload(http, this)
        assertTrue(d.start("pack", listOf(file("a", "one", "/a"))))
        assertFalse(d.start("pack", listOf(file("b", "two", "/b"))))
        advanceUntilIdle()
        assertEquals(1, server.requestCount)
        assertFalse(File(dir, "b").exists())
        // 结束以后同一个 key 可以再开。
        server.enqueue(MockResponse().setBody("two"))
        assertTrue(d.start("pack", listOf(file("b", "two", "/b"))))
        advanceUntilIdle()
        assertEquals("two", File(dir, "b").readText())
    }

    @Test fun cancelStopsConnectionAndDeletesPart() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val big = ByteArray(1_000_000) { 7 }
            server.enqueue(MockResponse().setBody(okio.Buffer().write(big)).throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("two"))
            val d = VerifiedDownload(http, scope)
            val target = File(dir, "big")
            d.start("pack", listOf(DownloadFile(target, listOf(url("/big")), {})))
            waitFor { d.progress("pack").value.doneBytes > 0 }
            assertTrue(File(dir, "big.part").exists())
            d.cancel("pack")
            assertEquals(DownloadPhase.Idle, d.progress("pack").value.phase)
            assertFalse(File(dir, "big.part").exists())
            // 新任务要等旧连接真正断掉才开始;断得慢(把 6 秒的慢响应读完)就等不到。
            val next = File(dir, "next")
            assertTrue(d.start("pack", listOf(DownloadFile(next, listOf(url("/next")), expect("two")))))
            waitFor(3_000) { d.progress("pack").value.phase == DownloadPhase.Ready }
            assertFalse(target.exists())
            assertFalse(File(dir, "big.part").exists())
            assertEquals("two", next.readText())
        } finally {
            scope.cancel()
        }
    }

    @Test fun alreadyVerifiedFileIsSkipped() = runTest {
        File(dir, "a").writeText("one")
        File(dir, "b").writeText("stale")
        server.enqueue(MockResponse().setBody("two"))
        val d = VerifiedDownload(http, this)
        d.start("pack", listOf(file("a", "one", "/a"), file("b", "two", "/b")))
        advanceUntilIdle()
        assertEquals(DownloadPhase.Ready, d.progress("pack").value.phase)
        assertEquals(1, server.requestCount)
        assertEquals("/b", server.takeRequest().path)
        assertEquals("two", File(dir, "b").readText())
    }

    @Test fun knownSizesGiveJobTotal() = runTest {
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        val d = VerifiedDownload(http, this)
        val files = listOf(file("a", "one", "/a").copy(size = 3), file("b", "two", "/b").copy(size = 3))
        val seen = mutableListOf<DownloadProgress>()
        backgroundScope.launch { d.progress("pack").collect { seen += it } }
        d.start("pack", files)
        advanceUntilIdle()
        assertTrue(seen.filter { it.phase == DownloadPhase.Downloading }.all { it.totalBytes == 6L })
        assertEquals(DownloadProgress(DownloadPhase.Ready, 6, 6), d.progress("pack").value)
    }

    private fun waitFor(timeoutMs: Long = 5_000, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!ok()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(10)
        }
    }
}
