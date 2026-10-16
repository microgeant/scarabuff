package com.scarabuff.core

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.scarabuff.core.FlatcInstaller.Asset
import com.scarabuff.core.FlatcInstaller.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FlatcInstallerTest {

    @TempDir
    lateinit var cacheDir: File

    private val fakeBinary = "#!/bin/sh\necho flatc version 1.2.3\n".toByteArray()

    private val zip: ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("flatc"))
            zip.write(fakeBinary)
            zip.closeEntry()
        }
    }.toByteArray()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun releases(sha256: String = sha256(zip)) =
        mapOf("1.2.3" to mapOf(Platform.LINUX_X64 to Asset("Linux.zip", sha256, "flatc")))

    private val downloads = mutableListOf<URI>()

    private fun install(version: String = "1.2.3", sha256: String = sha256(zip), platform: Platform? = Platform.LINUX_X64) =
        FlatcInstaller.install(version, cacheDir, platform, releases(sha256)) { uri ->
            downloads += uri
            zip.inputStream()
        }

    @Test
    fun `downloads, verifies and installs an executable`() {
        val flatc = install()

        assertThat(flatc.readBytes()).isEqualTo(fakeBinary)
        assertThat(flatc.canExecute()).isTrue()
        assertThat(flatc).isEqualTo(cacheDir.resolve("1.2.3/linux_x64/flatc"))
        assertThat(downloads.single().toString())
            .isEqualTo("https://github.com/google/flatbuffers/releases/download/v1.2.3/Linux.zip")
    }

    @Test
    fun `second install reuses the cache`() {
        install()
        install()

        assertThat(downloads).hasSize(1)
    }

    @Test
    fun `checksum mismatch fails and leaves nothing installed`() {
        val e = assertThrows<IllegalStateException> { install(sha256 = "0".repeat(64)) }

        assertThat(e).hasMessageThat().contains("Checksum mismatch")
        assertThat(cacheDir.resolve("1.2.3/linux_x64").listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `unknown version explains how to bring your own flatc`() {
        val e = assertThrows<IllegalArgumentException> { install(version = "9.9.9") }

        assertThat(e).hasMessageThat().contains("flatc 9.9.9")
        assertThat(e).hasMessageThat().contains("FLATC")
        assertThat(downloads).isEmpty()
    }

    @Test
    fun `unsupported platform fails without downloading`() {
        assertThrows<IllegalArgumentException> { install(platform = null) }
        assertThat(downloads).isEmpty()
    }

    @Test
    fun `detects platforms`() {
        assertThat(FlatcInstaller.currentPlatform("Mac OS X", "aarch64")).isEqualTo(Platform.MAC)
        assertThat(FlatcInstaller.currentPlatform("Linux", "amd64")).isEqualTo(Platform.LINUX_X64)
        assertThat(FlatcInstaller.currentPlatform("Linux", "aarch64")).isNull()
        assertThat(FlatcInstaller.currentPlatform("Windows 11", "amd64")).isEqualTo(Platform.WINDOWS_X64)
    }

    @Test
    fun `every pinned release covers all platforms with a well-formed checksum`() {
        for ((version, assets) in FlatcInstaller.RELEASES) {
            assertWithMessage("platforms for $version").that(assets.keys).containsExactlyElementsIn(Platform.entries)
            for (asset in assets.values) {
                assertThat(asset.sha256).matches("[0-9a-f]{64}")
            }
        }
    }

    @Test
    fun `platforms without an official binary get the exact build steps`() {
        val message = FlatcInstaller.noBinaryMessage("25.2.10", "Linux", "aarch64")

        assertThat(message).contains("(Linux aarch64)")
        assertThat(message).contains("git clone --depth 1 --branch v25.2.10 https://github.com/google/flatbuffers.git")
        assertThat(message).contains("cmake --build flatbuffers/build --target flatc --parallel")
        assertThat(message).contains("export FLATC=")
    }
}
