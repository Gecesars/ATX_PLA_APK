package com.gecesars.atxplan.data.dataset

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gecesars.atxplan.domain.dataset.IbgeDatasetPreparationPhase
import com.gecesars.atxplan.domain.dataset.IbgeDatasetException
import com.gecesars.atxplan.domain.dataset.IbgeDatasetFailure
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BundledIbgeDatasetRepositoryTest {
    @Test
    fun embeddedNationalIndexExtractsVerifiesReopensAndQueriesOffline() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(
            context.cacheDir,
            "ibge-repository-test-${System.nanoTime()}",
        ).canonicalFile
        val cacheRoot = context.cacheDir.canonicalFile
        assertTrue(root.path.startsWith(cacheRoot.path + File.separator))
        try {
            assertTrue(root.mkdirs())
            val supersededDatabase = File(root, "${"0".repeat(64)}.sqlite")
            supersededDatabase.writeText("superseded")
            val unrelatedFile = File(root, "keep.txt")
            unrelatedFile.writeText("unrelated")
            val phases = mutableListOf<IbgeDatasetPreparationPhase>()
            val repository = BundledIbgeDatasetRepository(
                assets = context.assets,
                installRoot = root,
                ioDispatcher = Dispatchers.IO,
                availableBytes = {
                    if (supersededDatabase.exists()) 0L else Long.MAX_VALUE
                },
            )

            val descriptor = repository.prepare { phases += it.phase }

            assertEquals("ibge-census-sectors-2022-brazil", descriptor.datasetId)
            assertEquals(468_099, descriptor.sectorCount)
            assertEquals(5_570, descriptor.municipalityCount)
            assertEquals(2, descriptor.unassignedSectorCount)
            assertEquals(0, descriptor.missingPopulationSectorCount)
            assertEquals(203_080_756L, descriptor.populationTotal)
            assertEquals("EPSG:4674", descriptor.sourceCrs)
            assertFalse(descriptor.geometryIncluded)
            assertTrue(phases.contains(IbgeDatasetPreparationPhase.INSTALLING))
            assertTrue(phases.contains(IbgeDatasetPreparationPhase.VALIDATING))
            assertFalse(supersededDatabase.exists())
            assertTrue(unrelatedFile.isFile)

            val matches = repository.searchMunicipalities("sao paulo", limit = 12)
            assertTrue(matches.isNotEmpty())
            val saoPaulo = matches.first()
            assertEquals("3550308", saoPaulo.code)
            assertEquals("São Paulo", saoPaulo.name)
            assertEquals("SP", saoPaulo.stateAbbreviation)
            assertEquals(11_451_999L, saoPaulo.populationTotal)
            assertEquals(27_301, saoPaulo.sectorCount)
            assertTrue(saoPaulo.urbanPopulation > saoPaulo.ruralPopulation)

            assertEquals(
                "3550308",
                repository.searchMunicipalities("3550308", limit = 3).single().code,
            )
            assertTrue(repository.searchMunicipalities("%", limit = 3).isEmpty())

            val databaseFile = root.listFiles()
                .orEmpty()
                .single { it.extension == "sqlite" }
            assertEquals(descriptor.installedByteCount, databaseFile.length())
            SQLiteDatabase.openDatabase(
                databaseFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            ).use { database ->
                database.rawQuery(
                    "SELECT count(*) FROM metadata WHERE key = 'source_root'",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                database.rawQuery(
                    "SELECT count(*) FROM sector WHERE municipality_code IS NULL AND population = 0",
                    null,
                ).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(2, cursor.getInt(0))
                }
            }

            val originalLength = databaseFile.length()
            RandomAccessFile(databaseFile, "rw").use { randomAccess ->
                randomAccess.seek(originalLength - 1L)
                val lastByte = randomAccess.read()
                randomAccess.seek(originalLength - 1L)
                randomAccess.write(lastByte xor 0x01)
            }
            assertEquals(originalLength, databaseFile.length())
            val recoveryPhases = mutableListOf<IbgeDatasetPreparationPhase>()
            val recovered = repository.prepare { recoveryPhases += it.phase }
            assertEquals(descriptor.databaseSha256, recovered.databaseSha256)
            assertTrue(recoveryPhases.contains(IbgeDatasetPreparationPhase.INSTALLING))
            assertEquals("3550308", repository.searchMunicipalities("sao paulo", 1).single().code)

            val reopenPhases = mutableListOf<IbgeDatasetPreparationPhase>()
            val reopenedRepository = BundledIbgeDatasetRepository(
                assets = context.assets,
                installRoot = root,
                ioDispatcher = Dispatchers.IO,
                availableBytes = { 0L },
            )
            val reopened = reopenedRepository.prepare { reopenPhases += it.phase }

            assertEquals(descriptor.databaseSha256, reopened.databaseSha256)
            assertFalse(reopenPhases.contains(IbgeDatasetPreparationPhase.INSTALLING))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun firstInstallFailsClosedWhenPrivateStorageIsInsufficient() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(
            context.cacheDir,
            "ibge-storage-test-${System.nanoTime()}",
        ).canonicalFile
        try {
            val repository = BundledIbgeDatasetRepository(
                assets = context.assets,
                installRoot = root,
                ioDispatcher = Dispatchers.IO,
                availableBytes = { 0L },
            )

            val error = try {
                repository.prepare()
                null
            } catch (failure: IbgeDatasetException) {
                failure
            }

            assertEquals(IbgeDatasetFailure.INSUFFICIENT_STORAGE, error?.failure)
            assertTrue(root.listFiles().orEmpty().none { it.extension == "sqlite" })
            assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".part") })
        } finally {
            root.deleteRecursively()
        }
    }
}
