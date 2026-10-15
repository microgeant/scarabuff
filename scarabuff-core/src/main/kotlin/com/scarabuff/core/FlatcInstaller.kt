package com.scarabuff.core

import java.io.File
import java.io.InputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Downloads the official flatc release binary for a FlatBuffers version, verifies it against a
 * pinned SHA-256, and caches it so later builds reuse it.
 *
 * Only versions listed in [RELEASES] can be installed: without a known checksum there's nothing to
 * verify the download against. Anything else needs an explicit `flatc` path.
 */
object FlatcInstaller {

    enum class Platform { MAC, LINUX_X64, WINDOWS_X64 }

    data class Asset(val fileName: String, val sha256: String, val executable: String)

    /**
     * Release assets from https://github.com/google/flatbuffers/releases. The Mac and Linux hashes
     * match the ones pinned independently in this repo's flake.nix; Windows was hashed from the
     * release download.
     *
     * To add a version: download each asset, `shasum -a 256` it, and add an entry here.
     */
    val RELEASES: Map<String, Map<Platform, Asset>> = mapOf(
        "25.2.10" to mapOf(
            // Universal binary: covers both Intel and Apple Silicon.
            Platform.MAC to Asset(
                "Mac.flatc.binary.zip",
                "eabd8e8c4d8b9381b7ea4c6324c629f9200d8bb73f94b6b28ef406efc8c06c62",
                "flatc",
            ),
            Platform.LINUX_X64 to Asset(
                "Linux.flatc.binary.g++-13.zip",
                "6f01258d7475806f375d6da66a61df47add8016edd73f1774673f37b80b9a711",
                "flatc",
            ),
            Platform.WINDOWS_X64 to Asset(
                "Windows.flatc.binary.zip",
                "bd9b3cb6bf5ab8009f1927b6e00207db968c3d5c93e3bc215dae084cc681249a",
                "flatc.exe",
            ),
        ),
    )

    fun currentPlatform(
        osName: String = System.getProperty("os.name"),
        osArch: String = System.getProperty("os.arch"),
    ): Platform? {
        val os = osName.lowercase()
        val x64 = osArch.lowercase() in setOf("x86_64", "amd64")
        return when {
            os.startsWith("mac") || os.startsWith("darwin") -> Platform.MAC
            os.startsWith("linux") && x64 -> Platform.LINUX_X64
            os.startsWith("windows") && x64 -> Platform.WINDOWS_X64
            else -> null
        }
    }

    /**
     * Returns the cached flatc for [version], downloading and verifying it first if needed. Safe
     * to call from several builds at once: each downloads into its own temporary file and the
     * finished binary is moved into place atomically.
     *
     * @param cacheDir root of the cache; binaries land in `<cacheDir>/<version>/<platform>/`
     * @param download opens a release asset URL; replaceable for tests
     */
    fun install(
        version: String,
        cacheDir: File,
        platform: Platform? = currentPlatform(),
        releases: Map<String, Map<Platform, Asset>> = RELEASES,
        download: (URI) -> InputStream = { it.toURL().openStream() },
    ): File {
        val assets = releases[version] ?: throw IllegalArgumentException(
            "scarabuff doesn't know the checksums for flatc $version (known: ${releases.keys.joinToString()}). " +
                "Use one of those FlatBuffers versions, or point scarabuff at your own flatc $version with " +
                "scarabuff { flatc.set(\"/path/to/flatc\") } or the FLATC environment variable."
        )
        val asset = platform?.let { assets[it] } ?: throw IllegalArgumentException(
            "There's no official flatc $version binary for this platform " +
                "(${System.getProperty("os.name")} ${System.getProperty("os.arch")}). " +
                "Build or install flatc $version yourself and set scarabuff { flatc.set(\"/path/to/flatc\") } " +
                "or the FLATC environment variable."
        )

        val installDir = cacheDir.resolve(version).resolve(platform.name.lowercase())
        val target = installDir.resolve(asset.executable)
        if (target.isFile && target.canExecute()) return target

        installDir.mkdirs()
        val url = URI("https://github.com/google/flatbuffers/releases/download/v$version/${asset.fileName}")
        val tempExecutable = Files.createTempFile(installDir.toPath(), asset.executable, ".tmp").toFile()
        try {
            extractVerified(download(url), asset, url, tempExecutable)
            tempExecutable.setExecutable(true)
            moveIntoPlace(tempExecutable, target)
        } finally {
            tempExecutable.delete()
        }
        return target
    }

    /** Streams the zip once: hashes every byte while extracting the executable entry. */
    private fun extractVerified(input: InputStream, asset: Asset, url: URI, destination: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        var found = false
        DigestInputStream(input.buffered(), digest).use { hashed ->
            val zip = ZipInputStream(hashed)
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == asset.executable) {
                    destination.outputStream().use { zip.copyTo(it) }
                    found = true
                }
            }
            // Drain anything after the last entry (e.g. the zip's central directory) so the hash
            // covers the whole file.
            hashed.readBytes()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == asset.sha256) {
            "Checksum mismatch for $url: expected ${asset.sha256}, got $actual. Refusing to use it."
        }
        check(found) { "$url doesn't contain ${asset.executable}" }
    }

    private fun moveIntoPlace(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: FileAlreadyExistsException) {
            // Another build installed it first; theirs is identical.
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
