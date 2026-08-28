package com.gecesars.atxplan.data.dataset

import com.gecesars.atxplan.domain.dataset.OVERPASS_MAX_RESPONSE_BYTES
import com.gecesars.atxplan.domain.dataset.RegionalBounds
import com.gecesars.atxplan.domain.dataset.RegionalDatasetPlanner
import com.gecesars.atxplan.domain.dataset.RegionalDatasetRequest
import com.gecesars.atxplan.domain.dataset.RegionalDatasetSelection
import com.gecesars.atxplan.domain.dataset.RegionalProcessingState
import com.gecesars.atxplan.domain.dataset.RegionalTransferStatus
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RegionalDatasetRepositoryTest {
    private lateinit var sandbox: File
    private lateinit var root: File
    private val nanoTicks = AtomicInteger()

    @Before
    fun setUp() {
        sandbox = Files.createTempDirectory(TEST_DIRECTORY_PREFIX).toFile()
        root = File(sandbox, "regional")
    }

    @After
    fun tearDown() {
        check(sandbox.name.startsWith(TEST_DIRECTORY_PREFIX))
        check(sandbox.deleteRecursively())
    }

    @Test
    fun `production transport rejects non HTTPS and unapproved hosts before opening a connection`() {
        val transport = AllowlistedHttpsRegionalHttpTransport()

        assertThrows(RegionalHttpSecurityException::class.java) {
            transport.execute(
                RegionalHttpRequest(
                    url = "http://copernicus-dem-30m.s3.eu-central-1.amazonaws.com/tile.tif",
                    method = RegionalHttpRequestMethod.GET,
                ),
            )
        }
        assertThrows(RegionalHttpSecurityException::class.java) {
            transport.execute(
                RegionalHttpRequest(
                    url = "https://example.invalid/tile.tif",
                    method = RegionalHttpRequestMethod.GET,
                ),
            )
        }
    }

    @Test
    fun `repository rejects a same host redirect to a different HTTPS port`() = runTest {
        val plan = buildingPlan()
        val artifact = plan.artifacts.single()
        val unsafeFinalUrl = artifact.url.replace(
            oldValue = "lambert.openstreetmap.de",
            newValue = "lambert.openstreetmap.de:444",
        )
        val transport = QueueTransport(
            {
                response(
                    url = unsafeFinalUrl,
                    status = 200,
                    bytes = "untrusted-response".toByteArray(),
                    etag = null,
                )
            },
        )

        val result = repository(transport).acquire(plan)

        assertEquals(RegionalTransferStatus.FAILED, result.results.single().status)
        assertTrue(result.results.single().error.orEmpty().contains("HTTPS origin"))
        assertEquals(1, transport.requests.size)
        assertFalse(File(root, artifact.relativePath).exists())
    }

    @Test
    fun `GET acquisition stores verified raw bytes and complete provenance inventory`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val payload = "verified-raw".toByteArray()
        val effectiveUrl = "${artifact.url}?served=regional-test"
        val processorCalls = AtomicInteger()
        val processorSourceUrls = mutableListOf<String>()
        val transport = QueueTransport(
            { request ->
                assertEquals(RegionalHttpRequestMethod.GET, request.method)
                assertNull(request.rangeStart)
                response(
                    effectiveUrl,
                    status = 200,
                    bytes = payload,
                    etag = "\"tile-v1\"",
                )
            },
        )
        val repository = repository(transport) { _, _, _, sourceUrl ->
            processorCalls.incrementAndGet()
            processorSourceUrls += sourceUrl
            RegionalProcessingOutcome(notes = "Validated by the test processor.")
        }

        val first = repository.acquire(plan)

        assertEquals(RegionalTransferStatus.READY, first.results.single().status)
        assertEquals(sha256(payload), first.results.single().sha256)
        assertArrayEquals(payload, File(root, artifact.relativePath).readBytes())
        assertEquals(1, processorCalls.get())
        assertEquals(listOf(effectiveUrl), processorSourceUrls)
        assertEquals(1, transport.requests.size)

        val inventory = repository.loadInventory()
        val record = checkNotNull(inventory.artifacts[artifact.relativePath])
        assertEquals(artifact.source.datasetId, record.datasetId)
        assertEquals(artifact.url, record.requestedUrl)
        assertEquals(effectiveUrl, record.effectiveUrl)
        assertEquals(artifact.source.routeId, record.routeId)
        assertEquals(artifact.source.routePolicyVersion, record.routePolicyVersion)
        assertEquals(artifact.source.toSourceSnapshot(), record.sourceSnapshot)
        assertEquals("2026-08-27T17:46:40.000Z", record.acquiredAt)
        assertEquals(artifact.source.sourceUrl, record.sourceUrl)
        assertEquals(artifact.source.license.id, record.licenseId)
        assertEquals(artifact.source.license.url, record.licenseUrl)
        assertEquals(artifact.source.license.attribution, record.attribution)
        assertEquals(artifact.source.provenance, record.provenance)
        assertEquals(RegionalProcessingState.READY, record.processingState)
        assertEquals(payload.size.toLong(), record.bytes)

        val second = repository.acquire(plan)

        assertEquals(RegionalTransferStatus.EXISTING, second.results.single().status)
        assertEquals(1, processorCalls.get())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `cancellation signalled by successful processing does not rewrite truthful ready inventory`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val payload = "truthful-ready-payload".toByteArray()
        val cancellationSignalled = AtomicBoolean()
        val transport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = payload,
                    etag = "\"truthful-ready-v1\"",
                )
            },
        )
        val repository = repository(transport)

        val result = repository.acquire(
            plan = plan,
            onProgress = { progress ->
                if (progress.status == RegionalTransferStatus.READY) {
                    cancellationSignalled.set(true)
                }
            },
            isCancelled = { cancellationSignalled.get() },
        )

        assertTrue(cancellationSignalled.get())
        assertEquals(RegionalTransferStatus.READY, result.results.single().status)
        assertArrayEquals(payload, File(root, artifact.relativePath).readBytes())
        val record = checkNotNull(repository.loadInventory().artifacts[artifact.relativePath])
        assertEquals(RegionalTransferStatus.READY, record.status)
        assertEquals(RegionalProcessingState.READY, record.processingState)
        assertEquals(sha256(payload), record.sha256)
    }

    @Test
    fun `processor coroutine cancellation propagates without publishing a failed inventory record`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val transport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = "processor-cancellation-payload".toByteArray(),
                    etag = "\"processor-cancellation-v1\"",
                )
            },
        )
        val repository = repository(
            transport = transport,
            processor = RegionalArtifactProcessor { _, _, _, _ ->
                throw CancellationException("Injected processor cancellation.")
            },
        )
        var propagated = false

        try {
            repository.acquireArtifact(plan = plan, artifactIndex = 0)
        } catch (_: CancellationException) {
            propagated = true
        }

        assertTrue(propagated)
        assertEquals(1, transport.requests.size)
        assertTrue(repository.loadInventory().artifacts.isEmpty())
    }

    @Test
    fun `immutable tile is reacquired when current plan bounds change within the same path`() = runTest {
        val firstPlan = rasterPlan()
        val secondPlan = RegionalDatasetPlanner().plan(
            firstPlan.request.copy(
                bounds = RegionalBounds(west = -46.69, south = -23.59, east = -46.61, north = -23.51),
            ),
        )
        val firstArtifact = firstPlan.artifacts.single()
        val secondArtifact = secondPlan.artifacts.single()
        assertEquals(firstArtifact.relativePath, secondArtifact.relativePath)
        assertFalse(firstArtifact.requestBounds == secondArtifact.requestBounds)
        val firstPayload = "first-bounds-payload".toByteArray()
        val secondPayload = "second-bounds-payload".toByteArray()
        val transport = QueueTransport(
            {
                response(
                    url = firstArtifact.url,
                    status = 200,
                    bytes = firstPayload,
                    etag = "\"bounds-v1\"",
                )
            },
            {
                response(
                    url = secondArtifact.url,
                    status = 200,
                    bytes = secondPayload,
                    etag = "\"bounds-v2\"",
                )
            },
        )
        val repository = repository(transport)

        assertEquals(RegionalTransferStatus.READY, repository.acquire(firstPlan).results.single().status)
        val secondResult = repository.acquire(secondPlan).results.single()

        assertEquals(RegionalTransferStatus.READY, secondResult.status)
        assertEquals(2, transport.requests.size)
        assertArrayEquals(secondPayload, File(root, secondArtifact.relativePath).readBytes())
        val record = checkNotNull(repository.loadInventory().artifacts[secondArtifact.relativePath])
        assertEquals(secondArtifact.requestBounds, record.bounds)
        assertEquals(sha256(secondPayload), record.sha256)
    }

    @Test
    fun `failed request for changed bounds does not preserve a stale immutable record`() = runTest {
        val firstPlan = rasterPlan()
        val secondPlan = RegionalDatasetPlanner().plan(
            firstPlan.request.copy(
                bounds = RegionalBounds(west = -46.69, south = -23.59, east = -46.61, north = -23.51),
            ),
        )
        val firstArtifact = firstPlan.artifacts.single()
        val secondArtifact = secondPlan.artifacts.single()
        assertEquals(firstArtifact.relativePath, secondArtifact.relativePath)
        val transport = QueueTransport(
            {
                response(
                    url = firstArtifact.url,
                    status = 200,
                    bytes = "verified-old-bounds".toByteArray(),
                    etag = "\"old-bounds-v1\"",
                )
            },
            {
                response(
                    url = secondArtifact.url,
                    status = 404,
                    bytes = ByteArray(0),
                    etag = null,
                )
            },
        )
        val repository = repository(transport)
        assertEquals(RegionalTransferStatus.READY, repository.acquire(firstPlan).results.single().status)

        val changedBoundsResult = repository.acquire(secondPlan).results.single()

        assertEquals(RegionalTransferStatus.NOT_FOUND, changedBoundsResult.status)
        assertEquals(2, transport.requests.size)
        val record = checkNotNull(repository.loadInventory().artifacts[secondArtifact.relativePath])
        assertEquals(RegionalTransferStatus.NOT_FOUND, record.status)
        assertEquals(secondArtifact.requestBounds, record.bounds)
        assertNull(record.bytes)
        assertNull(record.sha256)
    }

    @Test
    fun `single artifact seam reacquires an inventory record with substituted provenance`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val firstPayload = "trusted-provenance-payload".toByteArray()
        val secondPayload = "reacquired-provenance-payload".toByteArray()
        val transport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = firstPayload,
                    etag = "\"provenance-v1\"",
                )
            },
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = secondPayload,
                    etag = "\"provenance-v2\"",
                )
            },
        )
        val repository = repository(transport)
        assertEquals(RegionalTransferStatus.READY, repository.acquire(plan).results.single().status)
        val inventoryFile = File(root, ".atx-regional-inventory.json")
        val originalJson = inventoryFile.readText(Charsets.UTF_8)
        val substitutedJson = originalJson.replace(
            oldValue = JsonPrimitive(artifact.source.provenance).toString(),
            newValue = JsonPrimitive("Untrusted substituted provenance.").toString(),
        )
        assertFalse(originalJson == substitutedJson)
        inventoryFile.writeText(substitutedJson, Charsets.UTF_8)

        assertNull(repository.findCommittedArtifact(plan, artifactIndex = 0))
        val reacquired = repository.acquireArtifact(plan = plan, artifactIndex = 0)

        assertEquals(RegionalTransferStatus.READY, reacquired.result.status)
        assertEquals(2, transport.requests.size)
        assertArrayEquals(secondPayload, File(root, artifact.relativePath).readBytes())
        assertEquals(artifact.source.toSourceSnapshot(), reacquired.committedInventoryRecord.sourceSnapshot)
    }

    @Test
    fun `single artifact seam returns its exact committed record and bounded transfer evidence`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val payload = "single-artifact-evidence".toByteArray()
        val permitAttempts = mutableListOf<Int>()
        val transport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = payload,
                    etag = "\"artifact-v1\"",
                )
            },
        )
        val repository = repository(transport)

        val acquired = repository.acquireArtifact(
            plan = plan,
            artifactIndex = 0,
            beforeProviderAttempt = { attempt ->
                permitAttempts += attempt
                true
            },
        )

        assertEquals(RegionalTransferStatus.READY, acquired.result.status)
        assertEquals(1, acquired.providerAttempts)
        assertEquals(payload.size.toLong(), acquired.networkBytesTransferred)
        assertEquals(listOf(1), permitAttempts)
        assertEquals(
            acquired.committedInventoryRecord,
            repository.loadInventory().artifacts[artifact.relativePath],
        )
        assertEquals(
            acquired.committedInventoryRecord,
            repository.findCommittedArtifact(plan, artifactIndex = 0),
        )

        val cached = repository.acquireArtifact(
            plan = plan,
            artifactIndex = 0,
            maximumProviderAttempts = 0,
            beforeProviderAttempt = { throw AssertionError("A verified cache hit requested a provider permit.") },
        )

        assertEquals(RegionalTransferStatus.EXISTING, cached.result.status)
        assertEquals(0, cached.providerAttempts)
        assertEquals(0L, cached.networkBytesTransferred)
        assertEquals(1, transport.requests.size)
        assertEquals(
            cached.committedInventoryRecord,
            repository.findCommittedArtifact(plan, artifactIndex = 0),
        )

        File(root, artifact.relativePath).appendText("tampered", Charsets.UTF_8)
        assertNull(repository.findCommittedArtifact(plan, artifactIndex = 0))
    }

    @Test
    fun `single artifact attempt clamp and permit denial prevent excess provider requests`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val limitedRoot = File(root, "limited")
        val limitedTransport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 503,
                    bytes = ByteArray(0),
                    etag = null,
                )
            },
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = "must-not-run".toByteArray(),
                    etag = "\"unexpected\"",
                )
            },
        )
        val limitedPermits = mutableListOf<Int>()

        val limited = repository(
            transport = limitedTransport,
            repositoryRoot = limitedRoot,
        ).acquireArtifact(
            plan = plan,
            artifactIndex = 0,
            maximumProviderAttempts = 1,
            beforeProviderAttempt = { attempt ->
                limitedPermits += attempt
                true
            },
        )

        assertEquals(RegionalTransferStatus.FAILED, limited.result.status)
        assertEquals(1, limited.providerAttempts)
        assertEquals(0L, limited.networkBytesTransferred)
        assertEquals(listOf(1), limitedPermits)
        assertEquals(1, limitedTransport.requests.size)

        val zeroTransport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = "must-not-run".toByteArray(),
                    etag = "\"unexpected\"",
                )
            },
        )
        val zeroPermits = mutableListOf<Int>()
        val zero = repository(
            transport = zeroTransport,
            repositoryRoot = File(root, "zero"),
        ).acquireArtifact(
            plan = plan,
            artifactIndex = 0,
            maximumProviderAttempts = 0,
            beforeProviderAttempt = { attempt ->
                zeroPermits += attempt
                true
            },
        )

        assertEquals(RegionalTransferStatus.FAILED, zero.result.status)
        assertTrue(zero.result.error.orEmpty().contains("No provider attempts remain"))
        assertEquals(0, zero.providerAttempts)
        assertEquals(0L, zero.networkBytesTransferred)
        assertTrue(zeroPermits.isEmpty())
        assertTrue(zeroTransport.requests.isEmpty())

        val deniedTransport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = "must-not-run".toByteArray(),
                    etag = "\"unexpected\"",
                )
            },
        )
        val deniedPermits = mutableListOf<Int>()
        val denied = repository(
            transport = deniedTransport,
            repositoryRoot = File(root, "denied"),
        ).acquireArtifact(
            plan = plan,
            artifactIndex = 0,
            maximumProviderAttempts = 2,
            beforeProviderAttempt = { attempt ->
                deniedPermits += attempt
                false
            },
        )

        assertEquals(RegionalTransferStatus.CANCELLED, denied.result.status)
        assertEquals(listOf(1), deniedPermits)
        assertEquals(0, denied.providerAttempts)
        assertEquals(0L, denied.networkBytesTransferred)
        assertTrue(deniedTransport.requests.isEmpty())
    }

    @Test
    fun `single artifact evidence accumulates accepted bytes across provider retry attempts`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val permitAttempts = mutableListOf<Int>()
        val transport = QueueTransport(
            {
                RegionalHttpResponse(
                    statusCode = 200,
                    finalUrl = artifact.url,
                    contentLength = 6L,
                    contentRange = null,
                    etag = "\"v1\"",
                    lastModified = null,
                    body = FailingAfterBytesInputStream(
                        bytes = "abcdef".toByteArray(),
                        failureOffset = 3,
                        failure = SSLException("BAD_RECORD_MAC"),
                    ),
                    closeAction = {},
                )
            },
            { request ->
                assertEquals(3L, request.rangeStart)
                response(
                    url = artifact.url,
                    status = 206,
                    bytes = "def".toByteArray(),
                    etag = "\"v1\"",
                    contentRange = "bytes 3-5/6",
                )
            },
        )

        val acquired = repository(transport).acquireArtifact(
            plan = plan,
            artifactIndex = 0,
            maximumProviderAttempts = 2,
            beforeProviderAttempt = { attempt ->
                permitAttempts += attempt
                true
            },
        )

        assertEquals(RegionalTransferStatus.READY, acquired.result.status)
        assertEquals(2, acquired.providerAttempts)
        assertEquals(6L, acquired.networkBytesTransferred)
        assertEquals(listOf(1, 2), permitAttempts)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `batch acquisition matches sequential use of the committed artifact seam`() = runTest {
        val plan = multiRasterPlan()
        assertEquals(2, plan.artifacts.size)
        val payloads = plan.artifacts.associate { artifact ->
            artifact.url to "parity-${artifact.source.datasetId}".toByteArray()
        }
        val batchRoot = File(root, "batch")
        val seamRoot = File(root, "seam")
        val batchRepository = repository(
            transport = RegionalHttpTransport { request ->
                response(
                    url = request.url,
                    status = 200,
                    bytes = payloads.getValue(request.url),
                    etag = "\"parity\"",
                )
            },
            repositoryRoot = batchRoot,
        )
        val seamRepository = repository(
            transport = RegionalHttpTransport { request ->
                response(
                    url = request.url,
                    status = 200,
                    bytes = payloads.getValue(request.url),
                    etag = "\"parity\"",
                )
            },
            repositoryRoot = seamRoot,
        )

        val batch = batchRepository.acquire(plan)
        val seamResults = plan.artifacts.indices.map { artifactIndex ->
            seamRepository.acquireArtifact(plan, artifactIndex).result
        }

        assertEquals(batch.results, seamResults)
        assertEquals(batchRepository.loadInventory(), seamRepository.loadInventory())
        plan.artifacts.forEach { artifact ->
            assertArrayEquals(
                File(batchRoot, artifact.relativePath).readBytes(),
                File(seamRoot, artifact.relativePath).readBytes(),
            )
        }
    }

    @Test
    fun `committed artifact verification accepts explicit optional NoData without inventing a file`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        assertTrue(artifact.source.optionalWhenNotPublished)
        val repository = repository(
            QueueTransport(
                {
                    response(
                        url = artifact.url,
                        status = 404,
                        bytes = ByteArray(0),
                        etag = null,
                    )
                },
            ),
        )

        val acquired = repository.acquireArtifact(plan, artifactIndex = 0)

        assertEquals(RegionalTransferStatus.NOT_FOUND, acquired.result.status)
        assertEquals(1, acquired.providerAttempts)
        assertEquals(0L, acquired.networkBytesTransferred)
        assertFalse(File(root, artifact.relativePath).exists())
        assertEquals(
            acquired.committedInventoryRecord,
            repository.findCommittedArtifact(plan, artifactIndex = 0),
        )
    }

    @Test
    fun `forced live snapshot verification cannot adopt a record older than its durable intent`() = runTest {
        val acquisitionTime = 1_787_852_800_000L
        val plan = buildingPlan(liveSnapshotRefresh = true)
        val artifact = plan.artifacts.single()
        val repository = repository(
            transport = QueueTransport(
                {
                    response(
                        url = artifact.url,
                        status = 200,
                        bytes = "forced-live-snapshot".toByteArray(),
                        etag = null,
                    )
                },
            ),
            clock = { acquisitionTime },
        )

        val acquired = repository.acquireArtifact(plan, artifactIndex = 0)

        assertEquals(
            acquired.committedInventoryRecord,
            repository.findCommittedArtifact(
                plan = plan,
                artifactIndex = 0,
                minimumAcquiredAtEpochMillis = acquisitionTime,
            ),
        )
        assertNull(
            repository.findCommittedArtifact(
                plan = plan,
                artifactIndex = 0,
                minimumAcquiredAtEpochMillis = acquisitionTime + 1L,
            ),
        )
        assertNull(repository.findCommittedArtifact(plan, artifactIndex = 0))
    }

    @Test
    fun `expired live cache remains exact historical outcome evidence`() = runTest {
        var nowEpochMillis = 1_787_852_800_000L
        val plan = buildingPlan()
        val artifact = plan.artifacts.single()
        val repository = repository(
            transport = QueueTransport(
                {
                    response(
                        url = artifact.url,
                        status = 200,
                        bytes = "historical-live-snapshot".toByteArray(),
                        etag = null,
                    )
                },
            ),
            clock = { nowEpochMillis },
        )
        val acquired = repository.acquireArtifact(plan, artifactIndex = 0)
        assertNotNull(repository.findCommittedArtifact(plan, artifactIndex = 0))

        nowEpochMillis += checkNotNull(artifact.source.maximumCacheAgeMillis) + 1L

        assertNull(repository.findCommittedArtifact(plan, artifactIndex = 0))
        assertEquals(
            acquired.committedInventoryRecord,
            repository.findCommittedArtifactEvidence(plan, artifactIndex = 0),
        )
    }

    @Test
    fun `repository instances sharing one root serialize acquisitions and preserve inventory`() = runTest {
        val firstPlan = rasterPlan()
        val secondPlan = buildingPlan()
        val firstEnteredTransport = CountDownLatch(1)
        val releaseFirstResponse = CountDownLatch(1)
        val laterRequestEnteredTransport = CountDownLatch(1)
        val transport = BlockingRaceTransport(
            firstEntered = firstEnteredTransport,
            releaseFirst = releaseFirstResponse,
            laterRequestEntered = laterRequestEnteredTransport,
        )
        val firstRepository = repository(transport)
        val secondRepository = repository(transport)

        val firstAcquisition = async(Dispatchers.IO) { firstRepository.acquireArtifact(firstPlan, 0) }
        assertTrue(
            "The first acquisition did not reach the blocking transport.",
            firstEnteredTransport.await(5, TimeUnit.SECONDS),
        )
        val secondInvocationStarted = CountDownLatch(1)
        val secondAcquisition = async(Dispatchers.IO) {
            secondInvocationStarted.countDown()
            secondRepository.acquireArtifact(secondPlan, 0)
        }
        assertTrue(
            "The second acquisition coroutine did not start.",
            secondInvocationStarted.await(5, TimeUnit.SECONDS),
        )

        val enteredBeforeRelease = laterRequestEnteredTransport.await(1, TimeUnit.SECONDS)
        releaseFirstResponse.countDown()

        assertEquals(RegionalTransferStatus.READY, firstAcquisition.await().result.status)
        assertEquals(RegionalTransferStatus.READY, secondAcquisition.await().result.status)
        assertFalse(
            "A second repository entered its transport while the shared root was being mutated.",
            enteredBeforeRelease,
        )
        assertEquals(1, transport.maximumConcurrentRequests.get())
        assertEquals(2, transport.requestCount.get())
        val inventory = firstRepository.loadInventory()
        assertEquals(
            setOf(
                firstPlan.artifacts.single().relativePath,
                secondPlan.artifacts.single().relativePath,
            ),
            inventory.artifacts.keys,
        )
    }

    @Test
    fun `completed GET partial with a cross origin effective URL is discarded and redownloaded`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val forgedPayload = "forged-completed-partial".toByteArray()
        val freshPayload = "fresh-provider-response".toByteArray()
        seedCompletedPartial(
            url = artifact.url,
            relativePath = artifact.relativePath,
            bytes = forgedPayload,
            etag = "\"forged-v1\"",
            effectiveUrl = "https://esa-worldcover.s3.eu-central-1.amazonaws.com/forged.tif",
            acquiredAt = "2026-08-27T17:46:39.000Z",
        )
        val transport = QueueTransport(
            { request ->
                assertNull(request.rangeStart)
                assertNull(request.ifRangeEtag)
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = freshPayload,
                    etag = "\"fresh-v1\"",
                )
            },
        )
        val processedFreshPayload = AtomicBoolean()
        val repository = repository(transport) { _, raw, _, sourceUrl ->
            processedFreshPayload.set(raw.readBytes().contentEquals(freshPayload))
            assertEquals(artifact.url, sourceUrl)
            RegionalProcessingOutcome(notes = "Validated after discarding forged metadata.")
        }

        val result = repository.acquire(plan).results.single()

        assertEquals(RegionalTransferStatus.READY, result.status)
        assertTrue(processedFreshPayload.get())
        assertArrayEquals(freshPayload, File(root, artifact.relativePath).readBytes())
        assertEquals(artifact.url, result.effectiveUrl)
        assertEquals(artifact.url, repository.loadInventory().artifacts[artifact.relativePath]?.effectiveUrl)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `completed GET partial with an invalid or future acquisition time is redownloaded`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val forgedTimes = listOf(
            "not-a-timestamp",
            "2026-08-27T17:46:40.001Z",
        )

        forgedTimes.forEachIndexed { index, forgedTime ->
            val caseRoot = File(root, "timestamp-case-$index")
            val forgedPayload = "forged-$index".toByteArray()
            val freshPayload = "fresh-$index".toByteArray()
            seedCompletedPartial(
                url = artifact.url,
                relativePath = artifact.relativePath,
                bytes = forgedPayload,
                etag = "\"forged-$index\"",
                effectiveUrl = artifact.url,
                acquiredAt = forgedTime,
                repositoryRoot = caseRoot,
            )
            val transport = QueueTransport(
                { request ->
                    assertNull(request.rangeStart)
                    assertNull(request.ifRangeEtag)
                    response(
                        url = artifact.url,
                        status = 200,
                        bytes = freshPayload,
                        etag = "\"fresh-$index\"",
                    )
                },
            )
            val repository = repository(
                transport = transport,
                repositoryRoot = caseRoot,
            ) { _, raw, _, _ ->
                assertArrayEquals(freshPayload, raw.readBytes())
                RegionalProcessingOutcome(notes = "Validated after discarding invalid metadata.")
            }

            val result = repository.acquire(plan).results.single()

            assertEquals(RegionalTransferStatus.READY, result.status)
            assertEquals("2026-08-27T17:46:40.000Z", result.acquiredAt)
            assertArrayEquals(freshPayload, File(caseRoot, artifact.relativePath).readBytes())
            assertEquals(
                "2026-08-27T17:46:40.000Z",
                repository.loadInventory().artifacts[artifact.relativePath]?.acquiredAt,
            )
            assertEquals(1, transport.requests.size)
        }
    }

    @Test
    fun `GET resumes only from matching strong ETag and exact Content-Range start`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        seedPartial(artifact.url, artifact.relativePath, "abc".toByteArray(), "\"v1\"", totalBytes = 6L)
        val transport = QueueTransport(
            { request ->
                assertEquals(3L, request.rangeStart)
                assertEquals("\"v1\"", request.ifRangeEtag)
                response(
                    artifact.url,
                    status = 206,
                    bytes = "def".toByteArray(),
                    etag = "\"v1\"",
                    contentRange = "bytes 3-5/6",
                )
            },
        )

        val result = repository(transport).acquire(plan)

        assertEquals(RegionalTransferStatus.READY, result.results.single().status)
        assertArrayEquals("abcdef".toByteArray(), File(root, artifact.relativePath).readBytes())
        assertFalse(File(root, "${artifact.relativePath}.part").exists())
        assertFalse(File(root, "${artifact.relativePath}.part.json").exists())
    }

    @Test
    fun `HTTP 200 resets an attempted range instead of appending stale bytes`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        seedPartial(artifact.url, artifact.relativePath, "stale".toByteArray(), "\"old\"", totalBytes = 10L)
        val replacement = "replacement".toByteArray()
        val transport = QueueTransport(
            { request ->
                assertEquals(5L, request.rangeStart)
                response(
                    artifact.url,
                    status = 200,
                    bytes = replacement,
                    etag = "\"new\"",
                )
            },
        )

        val result = repository(transport).acquire(plan)

        assertEquals(RegionalTransferStatus.READY, result.results.single().status)
        assertArrayEquals(replacement, File(root, artifact.relativePath).readBytes())
    }

    @Test
    fun `malformed partial response is discarded without an automatic retry`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        seedPartial(artifact.url, artifact.relativePath, "abc".toByteArray(), "\"v1\"", totalBytes = 6L)
        val transport = QueueTransport(
            { request ->
                assertEquals(3L, request.rangeStart)
                response(
                    artifact.url,
                    status = 206,
                    bytes = "bad".toByteArray(),
                    etag = "\"v1\"",
                    contentRange = "bytes 0-2/6",
                )
            },
        )

        val result = repository(transport).acquire(plan)

        assertEquals(RegionalTransferStatus.FAILED, result.results.single().status)
        assertEquals(1, transport.requests.size)
        assertFalse(File(root, artifact.relativePath).exists())
        assertFalse(File(root, "${artifact.relativePath}.part").exists())
    }

    @Test
    fun `transient provider response retries the strongly validated GET partial`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        seedPartial(artifact.url, artifact.relativePath, "abc".toByteArray(), "\"v1\"", totalBytes = 6L)
        val transport = QueueTransport(
            { request ->
                assertEquals(3L, request.rangeStart)
                response(
                    artifact.url,
                    status = 503,
                    bytes = ByteArray(0),
                    etag = null,
                )
            },
            { request ->
                assertEquals(3L, request.rangeStart)
                assertEquals("\"v1\"", request.ifRangeEtag)
                response(
                    artifact.url,
                    status = 206,
                    bytes = "def".toByteArray(),
                    etag = "\"v1\"",
                    contentRange = "bytes 3-5/6",
                )
            },
        )

        val result = repository(transport).acquire(plan)

        assertEquals(RegionalTransferStatus.READY, result.results.single().status)
        assertEquals(2, transport.requests.size)
        assertArrayEquals("abcdef".toByteArray(), File(root, artifact.relativePath).readBytes())
    }

    @Test
    fun `HTTP 429 is replayed only after a valid bounded Retry-After delay`() = runTest {
        val plan = buildingPlan()
        val artifact = plan.artifacts.single()
        val retryDelays = mutableListOf<Long>()
        val transport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 429,
                    bytes = ByteArray(0),
                    etag = null,
                    retryAfter = "2",
                )
            },
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = "bounded-response".toByteArray(),
                    etag = null,
                )
            },
        )
        val repository = repository(
            transport = transport,
            retryDelay = retryDelays::add,
        )

        val result = repository.acquire(plan)

        assertEquals(RegionalTransferStatus.READY, result.results.single().status)
        assertEquals(listOf(2_000L), retryDelays)
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `HTTP 429 without a bounded Retry-After fails without replay`() = runTest {
        val plan = buildingPlan()
        val artifact = plan.artifacts.single()
        val transport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 429,
                    bytes = ByteArray(0),
                    etag = null,
                )
            },
        )

        val result = repository(transport).acquire(plan)

        assertEquals(RegionalTransferStatus.FAILED, result.results.single().status)
        assertTrue(result.results.single().error.orEmpty().contains("rate limiting"))
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun `replay safe Overpass POST retries a TLS read failure with clean staging`() = runTest {
        val plan = buildingPlan()
        val artifact = plan.artifacts.single()
        val firstPayload = "first-response".toByteArray()
        val successfulPayload = "second-complete-response".toByteArray()
        val processedPayloads = mutableListOf<ByteArray>()
        val progressMessages = mutableListOf<String>()
        val transport = QueueTransport(
            {
                RegionalHttpResponse(
                    statusCode = 200,
                    finalUrl = artifact.url,
                    contentLength = firstPayload.size.toLong(),
                    contentRange = null,
                    etag = null,
                    lastModified = null,
                    body = FailingAfterBytesInputStream(
                        bytes = firstPayload,
                        failureOffset = 5,
                        failure = SSLException("BAD_RECORD_MAC"),
                    ),
                    closeAction = {},
                )
            },
            { request ->
                assertEquals(RegionalHttpRequestMethod.POST, request.method)
                assertNull(request.rangeStart)
                response(
                    artifact.url,
                    status = 200,
                    bytes = successfulPayload,
                    etag = null,
                )
            },
        )
        val repository = repository(transport) { _, raw, _, _ ->
            processedPayloads += raw.readBytes()
            RegionalProcessingOutcome(notes = "Validated after retry.")
        }

        val result = repository.acquire(
            plan = plan,
            onProgress = { progress -> progressMessages += progress.message },
        )

        assertEquals(RegionalTransferStatus.READY, result.results.single().status)
        assertEquals(2, transport.requests.size)
        assertEquals(transport.requests[0].body, transport.requests[1].body)
        assertEquals(transport.requests[0].contentType, transport.requests[1].contentType)
        assertEquals(1, processedPayloads.size)
        assertArrayEquals(successfulPayload, processedPayloads.single())
        assertArrayEquals(successfulPayload, File(root, artifact.relativePath).readBytes())
        assertTrue(progressMessages.any { it.contains("Retrying attempt 2 of 2") })
    }

    @Test
    fun `GET retries a TLS read failure with strong ETag range resume`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val transport = QueueTransport(
            {
                RegionalHttpResponse(
                    statusCode = 200,
                    finalUrl = artifact.url,
                    contentLength = 6L,
                    contentRange = null,
                    etag = "\"v1\"",
                    lastModified = null,
                    body = FailingAfterBytesInputStream(
                        bytes = "abcdef".toByteArray(),
                        failureOffset = 3,
                        failure = SSLException("BAD_RECORD_MAC"),
                    ),
                    closeAction = {},
                )
            },
            { request ->
                assertEquals(3L, request.rangeStart)
                assertEquals("\"v1\"", request.ifRangeEtag)
                response(
                    artifact.url,
                    status = 206,
                    bytes = "def".toByteArray(),
                    etag = "\"v1\"",
                    contentRange = "bytes 3-5/6",
                )
            },
        )

        val result = repository(transport).acquire(plan)

        assertEquals(RegionalTransferStatus.READY, result.results.single().status)
        assertEquals(2, transport.requests.size)
        assertArrayEquals("abcdef".toByteArray(), File(root, artifact.relativePath).readBytes())
    }

    @Test
    fun `GET TLS retry without a strong ETag restarts from byte zero`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val replacement = "replacement".toByteArray()
        val transport = QueueTransport(
            {
                RegionalHttpResponse(
                    statusCode = 200,
                    finalUrl = artifact.url,
                    contentLength = 6L,
                    contentRange = null,
                    etag = null,
                    lastModified = null,
                    body = FailingAfterBytesInputStream(
                        bytes = "abcdef".toByteArray(),
                        failureOffset = 3,
                        failure = SSLException("BAD_RECORD_MAC"),
                    ),
                    closeAction = {},
                )
            },
            { request ->
                assertNull(request.rangeStart)
                assertNull(request.ifRangeEtag)
                response(
                    artifact.url,
                    status = 200,
                    bytes = replacement,
                    etag = "\"v2\"",
                )
            },
        )

        val result = repository(transport).acquire(plan)

        assertEquals(RegionalTransferStatus.READY, result.results.single().status)
        assertEquals(2, transport.requests.size)
        assertArrayEquals(replacement, File(root, artifact.relativePath).readBytes())
    }

    @Test
    fun `GET and replay safe POST stop at their separate retry budgets`() = runTest {
        val rasterPlan = rasterPlan()
        val getTransport = QueueTransport(
            { throw SSLException("GET failure 1") },
            { throw SSLException("GET failure 2") },
            { throw SSLException("GET failure 3") },
        )

        val getResult = repository(getTransport).acquire(rasterPlan)

        assertEquals(RegionalTransferStatus.FAILED, getResult.results.single().status)
        assertEquals(3, getTransport.requests.size)
        assertTrue(getResult.results.single().error.orEmpty().contains("after 3 attempts"))

        val buildingPlan = buildingPlan()
        val processorCalls = AtomicInteger()
        val postTransport = QueueTransport(
            { throw SSLException("POST failure 1") },
            { throw SSLException("POST failure 2") },
        )
        val postRepository = repository(postTransport) { _, _, _, _ ->
            processorCalls.incrementAndGet()
            RegionalProcessingOutcome()
        }

        val postResult = postRepository.acquire(buildingPlan)

        assertEquals(RegionalTransferStatus.FAILED, postResult.results.single().status)
        assertEquals(2, postTransport.requests.size)
        assertTrue(postResult.results.single().error.orEmpty().contains("after 2 attempts"))
        assertEquals(0, processorCalls.get())
        val artifact = buildingPlan.artifacts.single()
        assertFalse(File(root, "${artifact.relativePath}.part").exists())
    }

    @Test
    fun `security rejection and cancellation during backoff never issue a retry`() = runTest {
        val plan = buildingPlan()
        val securityTransport = QueueTransport(
            { throw RegionalHttpSecurityException("The regional-data host is not approved.") },
        )

        val securityResult = repository(securityTransport).acquire(plan)

        assertEquals(RegionalTransferStatus.FAILED, securityResult.results.single().status)
        assertEquals(1, securityTransport.requests.size)

        val cancelled = AtomicBoolean(false)
        val cancellationTransport = QueueTransport(
            { throw SSLException("Transient TLS failure") },
        )
        val cancellationRepository = repository(
            transport = cancellationTransport,
            retryDelay = { cancelled.set(true) },
        )

        val cancellationResult = cancellationRepository.acquire(
            plan = plan,
            isCancelled = cancelled::get,
        )

        assertEquals(RegionalTransferStatus.CANCELLED, cancellationResult.results.single().status)
        assertEquals(1, cancellationTransport.requests.size)
    }

    @Test
    fun `cooperative cancellation keeps a resumable GET partial but never a POST partial`() = runTest {
        val rasterPlan = rasterPlan()
        val rasterArtifact = rasterPlan.artifacts.single()
        val getCancellationChecks = AtomicInteger()
        val getTransport = QueueTransport(
            {
                RegionalHttpResponse(
                    statusCode = 200,
                    finalUrl = rasterArtifact.url,
                    contentLength = 6L,
                    contentRange = null,
                    etag = "\"v1\"",
                    lastModified = null,
                    body = ChunkedInputStream("abcdef".toByteArray(), 3),
                    closeAction = {},
                )
            },
        )

        val cancelledGet = repository(getTransport).acquire(
            plan = rasterPlan,
            isCancelled = { getCancellationChecks.incrementAndGet() >= 6 },
        )

        assertEquals(RegionalTransferStatus.CANCELLED, cancelledGet.results.single().status)
        assertArrayEquals("abc".toByteArray(), File(root, "${rasterArtifact.relativePath}.part").readBytes())
        assertTrue(File(root, "${rasterArtifact.relativePath}.part.json").isFile)

        val buildingPlan = buildingPlan()
        val buildingArtifact = buildingPlan.artifacts.single()
        val postCancellationChecks = AtomicInteger()
        val postTransport = QueueTransport(
            {
                RegionalHttpResponse(
                    statusCode = 200,
                    finalUrl = buildingArtifact.url,
                    contentLength = 6L,
                    contentRange = null,
                    etag = "\"ignored\"",
                    lastModified = null,
                    body = ChunkedInputStream("abcdef".toByteArray(), 3),
                    closeAction = {},
                )
            },
        )

        val cancelledPost = repository(postTransport).acquire(
            plan = buildingPlan,
            isCancelled = { postCancellationChecks.incrementAndGet() >= 6 },
        )

        assertEquals(RegionalTransferStatus.CANCELLED, cancelledPost.results.single().status)
        assertFalse(File(root, "${buildingArtifact.relativePath}.part").exists())
        assertFalse(File(root, "${buildingArtifact.relativePath}.part.json").exists())
    }

    @Test
    fun `cancelling before queued artifacts preserves their ready inventory records`() = runTest {
        val plan = multiRasterPlan()
        val payloads = plan.artifacts.mapIndexed { index, _ -> "ready-$index".toByteArray() }
        val transport = QueueTransport(
            *plan.artifacts.mapIndexed { index, artifact ->
                { _: RegionalHttpRequest ->
                    response(
                        url = artifact.url,
                        status = 200,
                        bytes = payloads[index],
                        etag = "\"ready-$index\"",
                    )
                }
            }.toTypedArray(),
        )
        val repository = repository(transport)

        val seeded = repository.acquire(plan)
        assertTrue(seeded.isSuccessful)
        assertEquals(plan.artifacts.size, repository.loadInventory().artifacts.size)

        val cancelled = repository.acquire(plan = plan, isCancelled = { true })

        assertTrue(cancelled.results.all { it.status == RegionalTransferStatus.CANCELLED })
        val preserved = repository.loadInventory()
        assertEquals(plan.artifacts.size, preserved.artifacts.size)
        assertTrue(preserved.artifacts.values.all { it.status == RegionalTransferStatus.READY })
        assertTrue(preserved.artifacts.values.all { it.processingState == RegionalProcessingState.READY })
        assertEquals(plan.artifacts.size, transport.requests.size)
    }

    @Test
    fun `schema 1 inventory migrates without inventing an effective URL or acquisition time`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val source = artifact.source
        val payload = "legacy-verified-raw".toByteArray()
        val digest = sha256(payload)
        val destination = File(root, artifact.relativePath)
        checkNotNull(destination.parentFile).mkdirs()
        destination.writeBytes(payload)
        root.mkdirs()
        File(root, ".atx-regional-inventory.json").writeText(
            """
            {
              "schemaVersion": 1,
              "artifacts": {
                ${JsonPrimitive(artifact.relativePath)}: {
                  "datasetId": ${JsonPrimitive(source.datasetId)},
                  "relativePath": ${JsonPrimitive(artifact.relativePath)},
                  "url": ${JsonPrimitive(artifact.url)},
                  "sourceUrl": ${JsonPrimitive(source.sourceUrl)},
                  "licenseId": ${JsonPrimitive(source.license.id)},
                  "licenseUrl": ${JsonPrimitive(source.license.url)},
                  "attribution": ${JsonPrimitive(source.license.attribution)},
                  "provenance": ${JsonPrimitive(source.provenance)},
                  "status": "READY",
                  "bytes": ${payload.size},
                  "sha256": "$digest",
                  "checkedAt": "2026-08-26T12:00:00.000Z",
                  "bounds": {"west":-46.7,"south":-23.6,"east":-46.6,"north":-23.5},
                  "processingState": "READY"
                }
              },
              "updatedAt": "2026-08-26T12:00:00.000Z",
              "lastBounds": {"west":-46.7,"south":-23.6,"east":-46.6,"north":-23.5}
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val transport = QueueTransport()
        val repository = repository(transport)

        val migrated = repository.loadInventory()

        assertEquals(2, migrated.schemaVersion)
        val record = checkNotNull(migrated.artifacts[artifact.relativePath])
        assertNull(record.effectiveUrl)
        assertNull(record.acquiredAt)
        assertEquals(source.datasetFamily, record.sourceSnapshot.datasetFamily)
        assertEquals(source.dataType, record.sourceSnapshot.dataType)
        assertEquals(source.fileFormat, record.sourceSnapshot.fileFormat)
        assertTrue(File(root, ".atx-regional-inventory.json").readText().contains("\"schemaVersion\":2"))

        val reused = repository.acquire(plan)
        assertEquals(RegionalTransferStatus.EXISTING, reused.results.single().status)
        assertTrue(transport.requests.isEmpty())
        val legacyCompatibleRecord = checkNotNull(repository.loadInventory().artifacts[artifact.relativePath])
        assertEquals(1, legacyCompatibleRecord.sourceSnapshot.catalogRevision)
        assertNull(legacyCompatibleRecord.effectiveUrl)
        assertNull(legacyCompatibleRecord.acquiredAt)
        assertNull(repository.findCommittedArtifact(plan, artifactIndex = 0))
    }

    @Test
    fun `schema 2 inventory with an invalid timestamp remains an explicit load failure`() = runTest {
        val plan = rasterPlan()
        val artifact = plan.artifacts.single()
        val transport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = "valid-before-forged-inventory".toByteArray(),
                    etag = "\"inventory-v1\"",
                )
            },
        )
        val repository = repository(transport)
        assertTrue(repository.acquire(plan).isSuccessful)
        val inventoryFile = File(root, ".atx-regional-inventory.json")
        val validPayload = inventoryFile.readText(Charsets.UTF_8)
        val invalidPayload = validPayload.replace(
            Regex("\"checkedAt\":\"[^\"]+\""),
            "\"checkedAt\":\"not-a-timestamp\"",
        )
        assertFalse("The valid fixture did not contain checkedAt.", invalidPayload == validPayload)
        inventoryFile.writeText(invalidPayload, Charsets.UTF_8)
        File(root, ".atx-regional-inventory.json.bak").delete()

        repeat(2) {
            val error = try {
                repository.loadInventory()
                throw AssertionError("The invalid schema 2 inventory was accepted.")
            } catch (error: RegionalDatasetException) {
                error
            }
            assertEquals(RegionalDatasetFailure.INVENTORY_INVALID, error.failure)
            assertTrue(error.message.orEmpty().contains("inventory is invalid"))
            assertEquals(invalidPayload, inventoryFile.readText(Charsets.UTF_8))
        }
    }

    @Test
    fun `valid schema 1 backup replaces an invalid primary during offline recovery`() = runTest {
        root.mkdirs()
        val legacyPayload = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("fixtures/regional_inventory_v1.json"),
        ).use { input -> input.readBytes() }
        val primary = File(root, ".atx-regional-inventory.json")
        val backup = File(root, ".atx-regional-inventory.json.bak")
        primary.writeText("{invalid", Charsets.UTF_8)
        backup.writeBytes(legacyPayload)
        val repository = repository(QueueTransport())

        val recovered = repository.loadInventory()

        assertEquals(2, recovered.schemaVersion)
        assertEquals(1, recovered.artifacts.size)
        assertTrue(primary.readText(Charsets.UTF_8).contains("\"schemaVersion\":2"))
        assertFalse(backup.exists())
    }

    @Test
    fun `live OSM cache expires after 24 hours and forced refresh always bypasses it`() = runTest {
        var now = 1_787_852_800_000L
        val cachedPlan = buildingPlan()
        val artifact = cachedPlan.artifacts.single()
        val transport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = "snapshot-one".toByteArray(),
                    etag = null,
                )
            },
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = "snapshot-two".toByteArray(),
                    etag = null,
                )
            },
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = "snapshot-three".toByteArray(),
                    etag = null,
                )
            },
        )
        val repository = repository(transport = transport, clock = { now })

        assertEquals(RegionalTransferStatus.READY, repository.acquire(cachedPlan).results.single().status)
        assertEquals(RegionalTransferStatus.EXISTING, repository.acquire(cachedPlan).results.single().status)
        assertEquals(1, transport.requests.size)

        now += 24L * 60L * 60L * 1_000L + 1L
        assertEquals(RegionalTransferStatus.READY, repository.acquire(cachedPlan).results.single().status)
        assertEquals(2, transport.requests.size)

        val forcedPlan = RegionalDatasetPlanner().plan(
            cachedPlan.request.copy(liveSnapshotRefresh = true),
        )
        assertEquals(RegionalTransferStatus.READY, repository.acquire(forcedPlan).results.single().status)
        assertEquals(3, transport.requests.size)
    }

    @Test
    fun `failed forced live refresh preserves the last verified ready snapshot`() = runTest {
        val cachedPlan = buildingPlan()
        val artifact = cachedPlan.artifacts.single()
        val verifiedPayload = "last-verified-snapshot".toByteArray()
        val transport = QueueTransport(
            {
                response(
                    url = artifact.url,
                    status = 200,
                    bytes = verifiedPayload,
                    etag = null,
                )
            },
            {
                response(
                    url = artifact.url,
                    status = 429,
                    bytes = ByteArray(0),
                    etag = null,
                )
            },
        )
        val repository = repository(transport)
        assertEquals(RegionalTransferStatus.READY, repository.acquire(cachedPlan).results.single().status)
        val readyRecord = checkNotNull(repository.loadInventory().artifacts[artifact.relativePath])
        val forcedPlan = RegionalDatasetPlanner().plan(
            cachedPlan.request.copy(liveSnapshotRefresh = true),
        )

        val refresh = repository.acquire(forcedPlan)

        assertEquals(RegionalTransferStatus.FAILED, refresh.results.single().status)
        assertTrue(refresh.results.single().error.orEmpty().contains("rate limiting"))
        assertEquals(readyRecord, repository.loadInventory().artifacts[artifact.relativePath])
        assertArrayEquals(verifiedPayload, File(root, artifact.relativePath).readBytes())
        assertEquals(2, transport.requests.size)
    }

    @Test
    fun `streaming cap rejects an oversized POST and removes its staging file`() = runTest {
        val plan = buildingPlan()
        val artifact = plan.artifacts.single()
        val processorCalls = AtomicInteger()
        val transport = QueueTransport(
            {
                RegionalHttpResponse(
                    statusCode = 200,
                    finalUrl = artifact.url,
                    contentLength = null,
                    contentRange = null,
                    etag = null,
                    lastModified = null,
                    body = SizedInputStream(OVERPASS_MAX_RESPONSE_BYTES + 1L),
                    closeAction = {},
                )
            },
        )
        val repository = repository(transport) { _, _, _, _ ->
            processorCalls.incrementAndGet()
            RegionalProcessingOutcome()
        }

        val result = repository.acquire(plan)

        assertEquals(RegionalTransferStatus.FAILED, result.results.single().status)
        assertTrue(result.results.single().error.orEmpty().contains("approved artifact limit"))
        assertEquals(0, processorCalls.get())
        assertFalse(File(root, artifact.relativePath).exists())
        assertFalse(File(root, "${artifact.relativePath}.part").exists())
    }

    private fun repository(
        transport: RegionalHttpTransport,
        retryDelay: suspend (Long) -> Unit = {},
        clock: () -> Long = { 1_787_852_800_000L },
        repositoryRoot: File = root,
        processor: RegionalArtifactProcessor = RegionalArtifactProcessor { _, _, _, _ ->
            RegionalProcessingOutcome(notes = "Validated by the test processor.")
        },
    ): FileRegionalDatasetRepository = FileRegionalDatasetRepository(
        rootDirectory = repositoryRoot,
        processor = processor,
        transport = transport,
        ioDispatcher = Dispatchers.Unconfined,
        availableBytes = { Long.MAX_VALUE },
        clock = clock,
        nanoClock = { nanoTicks.incrementAndGet().toLong() * 250_000_000L },
        retryDelay = retryDelay,
    )

    private fun rasterPlan() = RegionalDatasetPlanner().plan(
        RegionalDatasetRequest(
            bounds = RegionalBounds(west = -46.70, south = -23.60, east = -46.60, north = -23.50),
            selections = setOf(RegionalDatasetSelection.COPERNICUS_GLO_30_DSM),
            reason = "repository test",
        ),
    )

    private fun buildingPlan(liveSnapshotRefresh: Boolean = false) = RegionalDatasetPlanner().plan(
        RegionalDatasetRequest(
            bounds = RegionalBounds(west = -46.635, south = -23.555, east = -46.630, north = -23.550),
            selections = setOf(RegionalDatasetSelection.OSM_BUILDINGS_EXPERIMENTAL),
            reason = "repository test",
            liveSnapshotRefresh = liveSnapshotRefresh,
        ),
    )

    private fun multiRasterPlan() = RegionalDatasetPlanner().plan(
        RegionalDatasetRequest(
            bounds = RegionalBounds(west = -46.70, south = -23.60, east = -46.60, north = -23.50),
            selections = setOf(
                RegionalDatasetSelection.COPERNICUS_GLO_30_DSM,
                RegionalDatasetSelection.ESA_WORLDCOVER_2021,
            ),
            reason = "repository cancellation test",
        ),
    )

    private fun seedPartial(
        url: String,
        relativePath: String,
        bytes: ByteArray,
        etag: String,
        totalBytes: Long,
    ) {
        val target = File(root, relativePath)
        checkNotNull(target.parentFile).mkdirs()
        File("${target.path}.part").writeBytes(bytes)
        val escapedEtag = etag.replace("\"", "\\\"")
        File("${target.path}.part.json").writeText(
            """{"schemaVersion":1,"url":"$url","effectiveUrl":"$url","etag":"$escapedEtag","lastModified":null,"totalBytes":$totalBytes,"complete":false,"acquiredAt":null}""",
            Charsets.UTF_8,
        )
    }

    private fun seedCompletedPartial(
        url: String,
        relativePath: String,
        bytes: ByteArray,
        etag: String,
        effectiveUrl: String,
        acquiredAt: String,
        repositoryRoot: File = root,
    ) {
        val target = File(repositoryRoot, relativePath)
        checkNotNull(target.parentFile).mkdirs()
        File("${target.path}.part").writeBytes(bytes)
        val escapedEtag = etag.replace("\"", "\\\"")
        File("${target.path}.part.json").writeText(
            """{"schemaVersion":1,"url":"$url","effectiveUrl":"$effectiveUrl","etag":"$escapedEtag","lastModified":null,"totalBytes":${bytes.size},"complete":true,"acquiredAt":"$acquiredAt"}""",
            Charsets.UTF_8,
        )
    }
}

private class QueueTransport(
    vararg responses: (RegionalHttpRequest) -> RegionalHttpResponse,
) : RegionalHttpTransport {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<RegionalHttpRequest>()

    override fun execute(request: RegionalHttpRequest): RegionalHttpResponse {
        requests += request
        if (responses.isEmpty()) error("The test transport received an unexpected request.")
        return responses.removeFirst().invoke(request)
    }
}

private class BlockingRaceTransport(
    private val firstEntered: CountDownLatch,
    private val releaseFirst: CountDownLatch,
    private val laterRequestEntered: CountDownLatch,
) : RegionalHttpTransport {
    val requestCount = AtomicInteger()
    val maximumConcurrentRequests = AtomicInteger()
    private val activeRequests = AtomicInteger()

    override fun execute(request: RegionalHttpRequest): RegionalHttpResponse {
        val sequence = requestCount.incrementAndGet()
        val concurrent = activeRequests.incrementAndGet()
        maximumConcurrentRequests.accumulateAndGet(concurrent, ::maxOf)
        try {
            if (sequence == 1) {
                firstEntered.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS)) {
                    "The test did not release the first regional-data response."
                }
            } else {
                laterRequestEntered.countDown()
            }
            return response(
                url = request.url,
                status = 200,
                bytes = "race-payload-$sequence".toByteArray(),
                etag = if (request.method == RegionalHttpRequestMethod.GET) "\"race-$sequence\"" else null,
            )
        } finally {
            activeRequests.decrementAndGet()
        }
    }
}

private fun response(
    url: String,
    status: Int,
    bytes: ByteArray,
    etag: String?,
    contentRange: String? = null,
    retryAfter: String? = null,
): RegionalHttpResponse = RegionalHttpResponse(
    statusCode = status,
    finalUrl = url,
    contentLength = bytes.size.toLong(),
    contentRange = contentRange,
    etag = etag,
    lastModified = "Thu, 27 Aug 2026 12:00:00 GMT",
    retryAfter = retryAfter,
    body = ByteArrayInputStream(bytes),
    closeAction = {},
)

private class ChunkedInputStream(
    bytes: ByteArray,
    private val chunkBytes: Int,
) : ByteArrayInputStream(bytes) {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, minOf(length, chunkBytes))
}

private class FailingAfterBytesInputStream(
    private val bytes: ByteArray,
    private val failureOffset: Int,
    private val failure: IOException,
) : InputStream() {
    private var position = 0

    init {
        require(failureOffset in 0 until bytes.size)
    }

    override fun read(): Int {
        if (position >= failureOffset) throw failure
        return bytes[position++].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= failureOffset) throw failure
        val count = minOf(length, failureOffset - position)
        bytes.copyInto(buffer, destinationOffset = offset, startIndex = position, endIndex = position + count)
        position += count
        return count
    }
}

private class SizedInputStream(
    private var remaining: Long,
) : InputStream() {
    override fun read(): Int {
        if (remaining <= 0L) return -1
        remaining -= 1L
        return 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (remaining <= 0L) return -1
        val count = minOf(length.toLong(), remaining).toInt()
        java.util.Arrays.fill(buffer, offset, offset + count, 0.toByte())
        remaining -= count
        return count
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val TEST_DIRECTORY_PREFIX = "atx-regional-repository-test-"
