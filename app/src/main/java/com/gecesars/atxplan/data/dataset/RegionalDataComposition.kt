package com.gecesars.atxplan.data.dataset

import android.content.Context
import com.gecesars.atxplan.domain.dataset.RegionalJobRunner
import java.io.File

/** Single private-storage location shared by the screen-bound and durable execution paths. */
const val REGIONAL_DATA_DIRECTORY = "datasets/regional"

data class RegionalJobExecutionDependencies(
    val jobRepository: RegionalJobRepository,
    val runner: RegionalJobRunner,
)

/**
 * Reconstructs regional execution dependencies from application-private storage.
 *
 * A WorkManager process restart must not depend on an Activity or ViewModel instance. Keeping this
 * small composition boundary next to the repositories also prevents the durable worker and the
 * current screen-bound flow from drifting to different cache roots.
 */
object RegionalDataComposition {
    fun datasetRepository(context: Context): FileRegionalDatasetRepository =
        FileRegionalDatasetRepository(
            rootDirectory = File(
                context.applicationContext.noBackupFilesDir,
                REGIONAL_DATA_DIRECTORY,
            ),
            processor = DefaultRegionalArtifactProcessor(),
        )

    fun jobRepository(context: Context): FileRegionalJobRepository =
        FileRegionalJobRepository(context.applicationContext)

    fun jobExecutionDependencies(context: Context): RegionalJobExecutionDependencies {
        val jobRepository = jobRepository(context)
        return RegionalJobExecutionDependencies(
            jobRepository = jobRepository,
            runner = DefaultRegionalJobRunner(
                jobRepository = jobRepository,
                datasetRepository = datasetRepository(context),
            ),
        )
    }
}
