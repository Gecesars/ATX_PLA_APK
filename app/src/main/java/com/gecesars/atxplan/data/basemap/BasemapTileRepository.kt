package com.gecesars.atxplan.data.basemap

import android.content.Context
import android.graphics.BitmapFactory
import com.gecesars.atxplan.data.dataset.AllowlistedHttpsRegionalHttpTransport
import com.gecesars.atxplan.data.dataset.RegionalHttpRequest
import com.gecesars.atxplan.data.dataset.RegionalHttpRequestMethod
import com.gecesars.atxplan.data.dataset.RegionalHttpTransport
import com.gecesars.atxplan.domain.basemap.BasemapTileCoordinate
import com.gecesars.atxplan.domain.basemap.BasemapTilePlan
import com.gecesars.atxplan.domain.basemap.BasemapTilePlanner
import com.gecesars.atxplan.domain.basemap.RasterBasemapProvider
import com.gecesars.atxplan.domain.geo.GeographicCamera
import com.gecesars.atxplan.domain.geo.ViewportSizePx
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class CachedBasemapTile(
    val providerId: String,
    val coordinate: BasemapTileCoordinate,
    val absolutePath: String,
    val byteCount: Long,
    val fetchedAtEpochMillis: Long,
)

data class BasemapViewportLoad(
    val provider: RasterBasemapProvider,
    val plan: BasemapTilePlan,
    val tiles: List<CachedBasemapTile>,
    val downloadedCount: Int,
    val reusedCount: Int,
    val failureCount: Int,
    val firstFailure: String?,
    val cacheByteCount: Long,
)

fun interface BasemapTileFileValidator {
    fun isValid(file: File): Boolean
}

interface BasemapTileRepository {
    suspend fun loadVisibleTiles(
        provider: RasterBasemapProvider,
        camera: GeographicCamera,
        viewport: ViewportSizePx,
        displayTileSizePx: Double,
    ): BasemapViewportLoad
}

/**
 * Private, bounded cache for tiles that intersect the current interactive viewport.
 *
 * There is deliberately no bounding-box, multi-zoom, archive, or background-prefetch API.
 */
class FileBasemapTileRepository(
    private val cacheRoot: File,
    private val transportFactory: (RasterBasemapProvider) -> RegionalHttpTransport = { provider ->
        AllowlistedHttpsRegionalHttpTransport(
            allowedHosts = setOf(provider.allowedHost),
            connectTimeoutMillis = TILE_CONNECT_TIMEOUT_MILLIS,
            readTimeoutMillis = TILE_READ_TIMEOUT_MILLIS,
            maximumRedirects = 2,
            userAgent = TILE_USER_AGENT,
        )
    },
    private val validator: BasemapTileFileValidator = AndroidBasemapTileFileValidator,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : BasemapTileRepository {
    init {
        require(cacheRoot.path.isNotBlank()) { "The basemap cache root is invalid." }
    }

    override suspend fun loadVisibleTiles(
        provider: RasterBasemapProvider,
        camera: GeographicCamera,
        viewport: ViewportSizePx,
        displayTileSizePx: Double,
    ): BasemapViewportLoad = withContext(Dispatchers.IO) {
        val plan = BasemapTilePlanner.planVisibleTiles(
            provider = provider,
            camera = camera,
            viewport = viewport,
            displayTileSizePx = displayTileSizePx,
        )
        val transport = transportFactory(provider)
        val loaded = mutableListOf<CachedBasemapTile>()
        var downloadedCount = 0
        var reusedCount = 0
        val failures = mutableListOf<String>()

        val requestSlots = Semaphore(MAXIMUM_CONCURRENT_TILE_REQUESTS)
        val outcomes = plan.coordinates.map { coordinate ->
            async {
                requestSlots.withPermit {
                    coroutineContext.ensureActive()
                    try {
                        TileLoadOutcome.Success(loadTile(provider, coordinate, transport))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        TileLoadOutcome.Failure(error.safeTileMessage())
                    }
                }
            }
        }.awaitAll()
        outcomes.forEach { outcome ->
            when (outcome) {
                is TileLoadOutcome.Success -> {
                    loaded += outcome.result.tile
                    if (outcome.result.downloaded) downloadedCount += 1 else reusedCount += 1
                }
                is TileLoadOutcome.Failure -> failures += outcome.message
            }
        }
        evictCache(protectedPaths = loaded.mapTo(hashSetOf(), CachedBasemapTile::absolutePath))
        BasemapViewportLoad(
            provider = provider,
            plan = plan,
            tiles = loaded,
            downloadedCount = downloadedCount,
            reusedCount = reusedCount,
            failureCount = failures.size,
            firstFailure = failures.firstOrNull(),
            cacheByteCount = cacheRoot.cacheByteCount(),
        )
    }

    private fun loadTile(
        provider: RasterBasemapProvider,
        coordinate: BasemapTileCoordinate,
        transport: RegionalHttpTransport,
    ): TileLoadResult {
        val target = tileFile(provider, coordinate)
        synchronized(tileLock(target)) {
            val now = nowEpochMillis()
            if (target.isFreshAt(now) && validator.isValid(target)) {
                return TileLoadResult(target.toCachedTile(provider, coordinate), downloaded = false)
            }
            val staleIsValid = target.isFile && validator.isValid(target)
            return try {
                downloadTile(provider, coordinate, target, transport, now)
            } catch (error: Exception) {
                if (staleIsValid) {
                    TileLoadResult(target.toCachedTile(provider, coordinate), downloaded = false)
                } else {
                    throw error
                }
            }
        }
    }

    private fun downloadTile(
        provider: RasterBasemapProvider,
        coordinate: BasemapTileCoordinate,
        target: File,
        transport: RegionalHttpTransport,
        now: Long,
    ): TileLoadResult {
        target.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
                throw IOException("The private basemap cache directory could not be created.")
            }
        }
        val partial = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.part")
        try {
            transport.execute(
                RegionalHttpRequest(
                    url = provider.tileUrl(coordinate),
                    method = RegionalHttpRequestMethod.GET,
                    accept = "image/png,image/jpeg,image/webp;q=0.8",
                ),
            ).use { response ->
                if (response.statusCode != 200) {
                    throw IOException("The basemap provider returned HTTP ${response.statusCode}.")
                }
                if (response.contentLength != null && response.contentLength > MAX_TILE_BYTES) {
                    throw IOException("The basemap tile exceeds the per-file size limit.")
                }
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = response.body.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_TILE_BYTES) {
                            throw IOException("The basemap tile exceeds the per-file size limit.")
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (!validator.isValid(partial)) {
                throw IOException("The basemap provider returned an invalid raster tile.")
            }
            installAtomically(partial, target)
            target.setLastModified(now)
            return TileLoadResult(target.toCachedTile(provider, coordinate), downloaded = true)
        } finally {
            if (partial.exists()) partial.delete()
        }
    }

    private fun installAtomically(partial: File, target: File) {
        if (!target.exists()) {
            if (!partial.renameTo(target)) {
                throw IOException("The downloaded basemap tile could not be installed.")
            }
            return
        }
        val previous = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.previous")
        if (!target.renameTo(previous)) {
            throw IOException("The existing basemap tile could not be preserved.")
        }
        try {
            if (!partial.renameTo(target)) {
                previous.renameTo(target)
                throw IOException("The downloaded basemap tile could not be installed.")
            }
        } finally {
            previous.delete()
        }
    }

    private fun tileFile(
        provider: RasterBasemapProvider,
        coordinate: BasemapTileCoordinate,
    ): File = File(
        cacheRoot,
        "${provider.id}/${coordinate.zoom}/${coordinate.x}/${coordinate.y}.tile",
    )

    private fun evictCache(protectedPaths: Set<String>) {
        val files = cacheRoot.walkTopDown()
            .filter { file -> file.isFile && file.extension == "tile" }
            .toList()
        var totalBytes = files.sumOf(File::length)
        if (totalBytes <= MAX_CACHE_BYTES) return
        files.sortedBy(File::lastModified).forEach { file ->
            if (totalBytes <= MAX_CACHE_BYTES) return
            if (file.absolutePath !in protectedPaths) {
                val length = file.length()
                if (file.delete()) totalBytes -= length
            }
        }
    }

    private fun File.isFreshAt(now: Long): Boolean =
        isFile && length() in 1..MAX_TILE_BYTES &&
            lastModified() in 1..now && now - lastModified() <= MINIMUM_CACHE_TTL_MILLIS

    private fun File.toCachedTile(
        provider: RasterBasemapProvider,
        coordinate: BasemapTileCoordinate,
    ): CachedBasemapTile = CachedBasemapTile(
        providerId = provider.id,
        coordinate = coordinate,
        absolutePath = absolutePath,
        byteCount = length(),
        fetchedAtEpochMillis = lastModified(),
    )

    private fun File.cacheByteCount(): Long = walkTopDown()
        .filter { file -> file.isFile && file.extension == "tile" }
        .sumOf(File::length)

    companion object {
        fun create(context: Context): FileBasemapTileRepository = FileBasemapTileRepository(
            cacheRoot = File(context.noBackupFilesDir, CACHE_DIRECTORY_NAME),
        )
    }
}

private data class TileLoadResult(
    val tile: CachedBasemapTile,
    val downloaded: Boolean,
)

private sealed interface TileLoadOutcome {
    data class Success(val result: TileLoadResult) : TileLoadOutcome
    data class Failure(val message: String) : TileLoadOutcome
}

private object AndroidBasemapTileFileValidator : BasemapTileFileValidator {
    override fun isValid(file: File): Boolean {
        if (!file.isFile || file.length() !in MIN_TILE_BYTES..MAX_TILE_BYTES) return false
        val header = ByteArray(12)
        val headerCount = runCatching {
            file.inputStream().use { input -> input.read(header) }
        }.getOrDefault(0)
        if (!header.isSupportedRasterSignature(headerCount)) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth in 1..MAX_TILE_DIMENSION_PX &&
            options.outHeight in 1..MAX_TILE_DIMENSION_PX &&
            options.outMimeType in SUPPORTED_TILE_MIME_TYPES
    }
}

private fun ByteArray.isSupportedRasterSignature(length: Int): Boolean {
    val png = length >= 8 &&
        this[0] == 0x89.toByte() && this[1] == 0x50.toByte() &&
        this[2] == 0x4e.toByte() && this[3] == 0x47.toByte() &&
        this[4] == 0x0d.toByte() && this[5] == 0x0a.toByte() &&
        this[6] == 0x1a.toByte() && this[7] == 0x0a.toByte()
    val jpeg = length >= 3 &&
        this[0] == 0xff.toByte() && this[1] == 0xd8.toByte() && this[2] == 0xff.toByte()
    val webp = length >= 12 &&
        copyOfRange(0, 4).decodeToString() == "RIFF" &&
        copyOfRange(8, 12).decodeToString() == "WEBP"
    return png || jpeg || webp
}

private fun FileBasemapTileRepository.tileLock(file: File): Any =
    TILE_LOCKS.getOrPut(file.absolutePath) { Any() }

private fun Exception.safeTileMessage(): String = when (this) {
    is IOException -> message?.take(MAX_FAILURE_MESSAGE_LENGTH)
        ?: "A visible basemap tile could not be loaded."
    else -> "A visible basemap tile could not be loaded."
}

private val TILE_LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()
private val SUPPORTED_TILE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/webp")
private const val CACHE_DIRECTORY_NAME = "basemap-tiles-v1"
private const val TILE_USER_AGENT =
    "ATX-Plan-Android/0.1 (+https://github.com/Gecesars/ATX_PLA_APK)"
private const val TILE_CONNECT_TIMEOUT_MILLIS = 12_000
private const val TILE_READ_TIMEOUT_MILLIS = 20_000
private const val MAXIMUM_CONCURRENT_TILE_REQUESTS = 4
private const val COPY_BUFFER_BYTES = 32 * 1024
private const val MIN_TILE_BYTES = 32L
private const val MAX_TILE_BYTES = 2L * 1024L * 1024L
private const val MAX_TILE_DIMENSION_PX = 1_024
private const val MAX_CACHE_BYTES = 128L * 1024L * 1024L
private const val MINIMUM_CACHE_TTL_MILLIS = 7L * 24L * 60L * 60L * 1_000L
private const val MAX_FAILURE_MESSAGE_LENGTH = 240
