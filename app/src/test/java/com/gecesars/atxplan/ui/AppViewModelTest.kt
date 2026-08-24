package com.gecesars.atxplan.ui

import com.gecesars.atxplan.data.project.ProjectRepository
import com.gecesars.atxplan.data.project.ProjectStorageException
import com.gecesars.atxplan.domain.application.AppCoroutineDispatchers
import com.gecesars.atxplan.domain.application.AppUseCases
import com.gecesars.atxplan.domain.application.LinkBudgetCalculator
import com.gecesars.atxplan.domain.application.ProjectCreator
import com.gecesars.atxplan.domain.model.PlannerProject
import com.gecesars.atxplan.domain.model.ProjectCatalog
import com.gecesars.atxplan.domain.model.ProjectFactory
import com.gecesars.atxplan.domain.rf.LinkBudgetExecutionMode
import com.gecesars.atxplan.domain.rf.LinkBudgetInput
import com.gecesars.atxplan.domain.rf.LinkBudgetProvenance
import com.gecesars.atxplan.domain.rf.LinkBudgetResult
import com.gecesars.atxplan.domain.rf.RfCalculator
import com.gecesars.atxplan.ui.forms.RfPathDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private var projectSequence = 0L

    @Test
    fun `initial load exposes the persisted catalog`() = runTest(mainDispatcherRule.dispatcher) {
        val catalog = catalogWithProjects("Alpha", "Bravo")
        val repository = FakeProjectRepository(catalog)
        val viewModel = createViewModel(repository)

        assertTrue(viewModel.state.value.isLoading)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertTrue(viewModel.state.value.isCatalogWritable)
        assertEquals(catalog, viewModel.state.value.catalog)
        assertNull(viewModel.state.value.storageError)
        assertEquals(1, repository.loadCalls)
    }

    @Test
    fun `load failure publishes a stable fallback state`() = runTest(mainDispatcherRule.dispatcher) {
        val repository = FakeProjectRepository(ProjectCatalog()).apply {
            loadError = IllegalStateException()
        }
        val viewModel = createViewModel(repository)

        advanceUntilIdle()

        assertFalse(viewModel.state.value.isLoading)
        assertFalse(viewModel.state.value.isCatalogWritable)
        assertEquals(ProjectCatalog(), viewModel.state.value.catalog)
        assertEquals(
            "The local catalog could not be opened.",
            viewModel.state.value.storageError,
        )
        assertEquals(
            AppProblem(
                code = AppProblemCode.CATALOG_LOAD_FAILED,
                userMessage = "The local catalog could not be opened.",
                recoveryAction = AppRecoveryAction.RETRY_CATALOG_LOAD,
            ),
            viewModel.state.value.storageProblem,
        )
    }

    @Test
    fun `load failure blocks mutation until an explicit retry recovers the catalog`() =
        runTest(mainDispatcherRule.dispatcher) {
            val recoveredCatalog = catalogWithProjects("Recovered")
            val repository = FakeProjectRepository(ProjectCatalog()).apply {
                loadError = IllegalStateException("The catalog failed integrity validation.")
            }
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.createProject("Must Not Save", "")
            advanceUntilIdle()

            assertTrue(repository.savedCatalogs.isEmpty())
            assertEquals(ProjectCatalog(), viewModel.state.value.catalog)
            assertEquals(
                "The local catalog must load successfully before it can be changed.",
                viewModel.state.value.notice,
            )

            repository.catalogToLoad = recoveredCatalog
            repository.loadError = null
            viewModel.retryLoad()
            advanceUntilIdle()

            assertEquals(2, repository.loadCalls)
            assertTrue(viewModel.state.value.isCatalogWritable)
            assertEquals(recoveredCatalog, viewModel.state.value.catalog)

            viewModel.createProject("Safe Save", "")
            advanceUntilIdle()

            assertEquals(1, repository.savedCatalogs.size)
            assertEquals(
                listOf("Recovered", "Safe Save"),
                repository.savedCatalogs.single().projects.map { it.name },
            )
        }

    @Test
    fun `create action persists before publishing state and emits a consumable effect`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeProjectRepository(catalogWithProjects("Existing"))
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.onAction(AppUiAction.CreateProject("  Ridge Link  ", "  Carrier A  "))
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals(listOf("Existing", "Ridge Link"), state.catalog.projects.map { it.name })
            assertEquals("Ridge Link", state.selectedProject?.name)
            assertEquals("Carrier A", state.selectedProject?.customer)
            assertEquals(state.catalog, repository.savedCatalogs.single())
            assertEquals(
                AppUiEffect.ShowNotice("Project \"Ridge Link\" was created in local storage."),
                state.pendingEffect,
            )
            assertEquals(
                "Project \"Ridge Link\" was created in local storage.",
                state.notice,
            )

            viewModel.onAction(AppUiAction.DismissNotice)

            assertNull(viewModel.state.value.pendingEffect)
            assertNull(viewModel.state.value.notice)
        }

    @Test
    fun `invalid project data emits an effect without attempting a save`() =
        runTest(mainDispatcherRule.dispatcher) {
            val initial = catalogWithProjects("Existing")
            val repository = FakeProjectRepository(initial)
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.createProject("x", "Customer")
            advanceUntilIdle()

            assertEquals(initial, viewModel.state.value.catalog)
            assertTrue(
                viewModel.state.value.notice.orEmpty()
                    .contains("between 2 and 80 characters"),
            )
            assertTrue(repository.savedCatalogs.isEmpty())
        }

    @Test
    fun `save failure leaves the last durable catalog visible`() =
        runTest(mainDispatcherRule.dispatcher) {
            val initial = catalogWithProjects("Durable")
            val repository = FakeProjectRepository(initial).apply {
                saveError = ProjectStorageException("Storage is read-only.")
            }
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.createProject("Unsaved", "")
            advanceUntilIdle()

            assertEquals(initial, viewModel.state.value.catalog)
            assertEquals("Storage is read-only.", viewModel.state.value.storageError)
            assertEquals(AppProblemCode.CATALOG_SAVE_FAILED, viewModel.state.value.storageProblem?.code)
            assertFalse(viewModel.state.value.isSavingCatalog)
            assertNull(viewModel.state.value.pendingEffect)
        }

    @Test
    fun `rejected and no-op mutations preserve an unresolved storage failure`() =
        runTest(mainDispatcherRule.dispatcher) {
            val initial = catalogWithProjects("Durable")
            val repository = FakeProjectRepository(initial).apply {
                saveError = ProjectStorageException("Storage remains unavailable.")
            }
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.createProject("Failed Save", "")
            advanceUntilIdle()
            val unresolvedProblem = viewModel.state.value.storageProblem

            repository.saveError = null
            viewModel.createProject("x", "")
            advanceUntilIdle()
            assertEquals(unresolvedProblem, viewModel.state.value.storageProblem)

            viewModel.selectProject("missing")
            advanceUntilIdle()
            assertEquals(unresolvedProblem, viewModel.state.value.storageProblem)
            assertTrue(repository.savedCatalogs.isEmpty())

            viewModel.createProject("Recovered Save", "")
            advanceUntilIdle()
            assertNull(viewModel.state.value.storageProblem)
            assertEquals(1, repository.savedCatalogs.size)
        }

    @Test
    fun `rapid project mutations are serialized without losing an update`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeProjectRepository(catalogWithProjects("Initial")).apply {
                saveDelayMillis = 50L
            }
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.createProject("First", "")
            viewModel.createProject("Second", "")
            advanceUntilIdle()

            assertEquals(
                listOf("Initial", "First", "Second"),
                viewModel.state.value.catalog.projects.map { it.name },
            )
            assertEquals(listOf(2, 3), repository.savedCatalogs.map { it.projects.size })
            assertEquals("Second", viewModel.state.value.selectedProject?.name)
        }

    @Test
    fun `separate view models rebase catalog mutations on the latest durable state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val repository = FakeProjectRepository(catalogWithProjects("Initial")).apply {
                saveDelayMillis = 25L
            }
            val firstViewModel = createViewModel(repository)
            val secondViewModel = createViewModel(repository)
            advanceUntilIdle()

            firstViewModel.createProject("First Client", "")
            secondViewModel.createProject("Second Client", "")
            advanceUntilIdle()

            assertEquals(
                listOf("Initial", "First Client", "Second Client"),
                repository.catalogToLoad.projects.map { it.name },
            )
            assertEquals(listOf(2, 3), repository.savedCatalogs.map { it.projects.size })
        }

    @Test
    fun `selection ignores unknown and already selected projects`() =
        runTest(mainDispatcherRule.dispatcher) {
            val initial = catalogWithProjects("Alpha", "Bravo")
            val repository = FakeProjectRepository(initial)
            val viewModel = createViewModel(repository)
            advanceUntilIdle()

            viewModel.selectProject("missing")
            viewModel.selectProject(initial.selectedProjectId.orEmpty())
            viewModel.selectProject(initial.projects.last().id)
            advanceUntilIdle()

            assertEquals(initial.projects.last().id, viewModel.state.value.catalog.selectedProjectId)
            assertEquals(1, repository.savedCatalogs.size)
        }

    @Test
    fun `complete RF path is committed before linked entities enter UI state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val initial = catalogWithProjects("Path Project")
            val repository = FakeProjectRepository(initial)
            val viewModel = createViewModel(repository)
            advanceUntilIdle()
            val projectId = initial.selectedProjectId.orEmpty()

            viewModel.addRfPath(RfPathDraft().toCommand(projectId).getOrThrow())
            advanceUntilIdle()

            val project = viewModel.state.value.selectedProject!!
            assertEquals(1, project.networks.size)
            assertEquals(1, project.sites.size)
            assertEquals(1, project.sites.single().sectors.size)
            assertEquals(1, project.receivers.size)
            assertEquals(project.networks.single().id, project.sites.single().sectors.single().networkId)
            assertEquals(project.networks.single().id, project.receivers.single().networkId)
            assertEquals(viewModel.state.value.catalog, repository.savedCatalogs.single())
            assertTrue(viewModel.state.value.notice.orEmpty().contains("was saved"))
        }

    @Test
    fun `catalog mutation waits for initial load instead of overwriting it`() =
        runTest(mainDispatcherRule.dispatcher) {
            val loadGate = CompletableDeferred<Unit>()
            val repository = FakeProjectRepository(catalogWithProjects("Loaded")).apply {
                this.loadGate = loadGate
            }
            val viewModel = createViewModel(repository)

            viewModel.createProject("Queued", "")
            runCurrent()
            assertTrue(viewModel.state.value.isLoading)
            assertTrue(repository.savedCatalogs.isEmpty())

            loadGate.complete(Unit)
            advanceUntilIdle()

            assertEquals(
                listOf("Loaded", "Queued"),
                viewModel.state.value.catalog.projects.map { it.name },
            )
            assertEquals(1, repository.savedCatalogs.size)
        }

    @Test
    fun `calculation success and failure have explicit progress and result states`() =
        runTest(mainDispatcherRule.dispatcher) {
            var calculationError: Throwable? = null
            val repository = FakeProjectRepository(ProjectCatalog())
            val viewModel = createViewModel(
                repository = repository,
                calculator = LinkBudgetCalculator { input ->
                    calculationError?.let { throw it }
                    RfCalculator.linkBudget(input)
                },
            )
            advanceUntilIdle()

            viewModel.calculateLinkBudget(validLinkBudgetInput())
            assertTrue(viewModel.state.value.isCalculating)
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isCalculating)
            assertTrue(viewModel.state.value.linkBudgetResult != null)
            assertEquals(validLinkBudgetInput(), viewModel.state.value.linkBudgetInput)
            assertNull(viewModel.state.value.calculatorError)

            calculationError = IllegalArgumentException("The test input is invalid.")
            viewModel.calculateLinkBudget(validLinkBudgetInput())
            advanceUntilIdle()

            assertFalse(viewModel.state.value.isCalculating)
            assertNull(viewModel.state.value.linkBudgetInput)
            assertNull(viewModel.state.value.linkBudgetResult)
            assertEquals("The test input is invalid.", viewModel.state.value.calculatorError)
            assertEquals(
                AppRecoveryAction.EDIT_LINK_PARAMETERS,
                viewModel.state.value.calculatorProblem?.recoveryAction,
            )
        }

    @Test
    fun `replaceable calculator provenance reaches UI state unchanged`() =
        runTest(mainDispatcherRule.dispatcher) {
            val provenance = testProvenance(modelLabel = "Laboratory Reference Model")
            val calculatedResult = linkBudgetResult(
                receivedPowerDbm = -81.0,
                provenance = provenance,
            )
            val viewModel = createViewModel(
                repository = FakeProjectRepository(ProjectCatalog()),
                calculator = LinkBudgetCalculator { calculatedResult },
            )
            advanceUntilIdle()

            val input = validLinkBudgetInput()
            viewModel.calculateLinkBudget(input)
            advanceUntilIdle()

            assertEquals(input, viewModel.state.value.linkBudgetInput)
            assertSame(calculatedResult, viewModel.state.value.linkBudgetResult)
            assertSame(provenance, viewModel.state.value.linkBudgetResult?.provenance)
        }

    @Test
    fun `a newer calculation cancels a stale request`() = runTest(mainDispatcherRule.dispatcher) {
        val slowResult = linkBudgetResult(receivedPowerDbm = -100.0)
        val latestResult = linkBudgetResult(receivedPowerDbm = -70.0)
        val repository = FakeProjectRepository(ProjectCatalog())
        val viewModel = createViewModel(
            repository = repository,
            calculator = LinkBudgetCalculator { input ->
                if (input.distanceKm == 1.0) {
                    delay(1_000L)
                    slowResult
                } else {
                    delay(10L)
                    latestResult
                }
            },
        )
        advanceUntilIdle()

        viewModel.calculateLinkBudget(validLinkBudgetInput(distanceKm = 1.0))
        runCurrent()
        viewModel.calculateLinkBudget(validLinkBudgetInput(distanceKm = 2.0))
        advanceUntilIdle()

        assertFalse(viewModel.state.value.isCalculating)
        assertSame(latestResult, viewModel.state.value.linkBudgetResult)
        assertNull(viewModel.state.value.calculatorError)
    }

    private fun createViewModel(
        repository: ProjectRepository,
        calculator: LinkBudgetCalculator = LinkBudgetCalculator { input ->
            RfCalculator.linkBudget(input)
        },
    ): AppViewModel {
        val dispatchers = AppCoroutineDispatchers(
            storage = mainDispatcherRule.dispatcher,
            computation = mainDispatcherRule.dispatcher,
        )
        val projectCreator = ProjectCreator { name, customer ->
            projectSequence += 1
            ProjectFactory.create(name, customer, nowEpochMillis = projectSequence)
                .copy(id = "project-test-$projectSequence")
        }
        return AppViewModel(
            AppUseCases.create(
                repository = repository,
                dispatchers = dispatchers,
                projectCreator = projectCreator,
                linkBudgetCalculator = calculator,
            ),
        )
    }

    private fun catalogWithProjects(vararg names: String): ProjectCatalog {
        val projects = names.mapIndexed { index, name ->
            PlannerProject(
                id = "existing-$index",
                name = name,
                createdAtEpochMillis = index.toLong(),
                updatedAtEpochMillis = index.toLong(),
            )
        }
        return ProjectCatalog(
            selectedProjectId = projects.firstOrNull()?.id,
            projects = projects,
        )
    }

    private fun validLinkBudgetInput(distanceKm: Double = 10.0) = LinkBudgetInput(
        frequencyMHz = 900.0,
        distanceKm = distanceKm,
        transmitPowerDbm = 43.0,
        transmitAntennaGainDbi = 15.0,
        transmitLossDb = 2.0,
        receiveAntennaGainDbi = 12.0,
        receiveLossDb = 1.0,
        additionalPathLossDb = 3.0,
        receiverSensitivityDbm = -100.0,
        bandwidthMHz = 10.0,
        receiverNoiseFigureDb = 5.0,
    )

    private fun linkBudgetResult(
        receivedPowerDbm: Double,
        provenance: LinkBudgetProvenance = testProvenance(),
    ) = LinkBudgetResult(
        freeSpacePathLossDb = 100.0,
        eirpDbm = 50.0,
        receivedPowerDbm = receivedPowerDbm,
        fadeMarginDb = 20.0,
        firstFresnelMidpointRadiusM = 10.0,
        noiseFloorDbm = -99.0,
        signalToNoiseDb = 29.0,
        provenance = provenance,
    )

    private fun testProvenance(
        modelLabel: String = "Test Reference Model",
    ) = LinkBudgetProvenance(
        modelId = "test-reference-model",
        modelLabel = modelLabel,
        implementationId = "test-engine-v1",
        implementationLabel = "Test Engine v1",
        executionMode = LinkBudgetExecutionMode.LOCAL,
        dataProvenance = "Synthetic test values",
        methodology = "Deterministic values supplied by the test calculator.",
        limitations = "This result is valid only inside the unit test.",
    )

    private class FakeProjectRepository(
        var catalogToLoad: ProjectCatalog,
    ) : ProjectRepository {
        var loadCalls: Int = 0
        var loadError: Throwable? = null
        var saveError: Throwable? = null
        var saveDelayMillis: Long = 0L
        var loadGate: CompletableDeferred<Unit>? = null
        val savedCatalogs = mutableListOf<ProjectCatalog>()
        private val updateMutex = Mutex()

        override suspend fun loadCatalog(): ProjectCatalog {
            loadCalls += 1
            loadGate?.await()
            loadError?.let { throw it }
            return catalogToLoad
        }

        override suspend fun updateCatalog(
            transform: (ProjectCatalog) -> ProjectCatalog,
        ): ProjectCatalog = updateMutex.withLock {
            val updatedCatalog = transform(catalogToLoad)
            if (saveDelayMillis > 0L) delay(saveDelayMillis)
            saveError?.let { throw it }
            if (updatedCatalog != catalogToLoad) {
                savedCatalogs += updatedCatalog
                catalogToLoad = updatedCatalog
            }
            updatedCatalog
        }
    }
}
