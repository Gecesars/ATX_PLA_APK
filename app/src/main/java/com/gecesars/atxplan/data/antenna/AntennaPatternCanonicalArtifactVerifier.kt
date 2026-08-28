package com.gecesars.atxplan.data.antenna

import com.gecesars.atxplan.domain.antenna.CanonicalAntennaPattern
import com.gecesars.atxplan.domain.application.calculateNormalizedContentSha256
import com.gecesars.atxplan.domain.application.hasVerifiedNormalizedContentIdentity
import com.gecesars.atxplan.domain.model.AntennaPatternRecord
import com.gecesars.atxplan.domain.model.ProjectArtifactReference
import com.gecesars.atxplan.domain.model.ProjectArtifactRole
import java.security.MessageDigest

data class VerifiedCanonicalAntennaArtifact(
    val pattern: CanonicalAntennaPattern,
    val metadata: AntennaPatternFileMetadata,
    val formatVersion: Int,
)

/**
 * Verifies the immutable native artifact before an engineering consumer uses information that the
 * fixed-grid project cache cannot represent losslessly, including per-sample phase NoData.
 */
object AntennaPatternCanonicalArtifactVerifier {
    fun verify(
        record: AntennaPatternRecord,
        artifact: ProjectArtifactReference,
        payload: ByteArray,
    ): VerifiedCanonicalAntennaArtifact {
        require(record.hasVerifiedNormalizedContentIdentity()) {
            "The antenna project cache has an invalid normalized content identity."
        }
        require(record.dataArtifactId == artifact.id) {
            "The antenna record does not reference the supplied canonical artifact."
        }
        require(artifact.role == ProjectArtifactRole.ANTENNA_PATTERN) {
            "The canonical antenna artifact has an invalid project role."
        }
        require(payload.size.toLong() == artifact.byteCount) {
            "The canonical antenna artifact byte count does not match its project reference."
        }
        require(sha256(payload) == artifact.sha256) {
            "The canonical antenna artifact payload does not match its project SHA-256."
        }
        val canonical = AntennaPatternCodec.parse(payload, artifact.fileName)
        require(canonical.detectedFormat == AntennaPatternFileFormat.ATX_ANTENNA_JSON_V1) {
            "The canonical antenna artifact is not ATX Antenna JSON."
        }
        val formatVersion = canonical.formatVersion
        require(formatVersion == 1 || formatVersion == 2) {
            "The canonical antenna artifact has no supported schema version."
        }
        require(canonical.sourceSha256 == artifact.sha256) {
            "The canonical antenna artifact failed source-hash correlation."
        }
        require(canonical.isCalculationReady) {
            "The canonical antenna artifact contains review-only or legacy cut availability."
        }
        val metadata = when (formatVersion) {
            1 -> canonical.metadata.copy(declaredGainDbi = record.peakGainDbi)
            2 -> canonical.metadata
            else -> error("Unsupported canonical antenna schema version.")
        }
        val canonicalIdentity = canonical.pattern.calculateNormalizedContentSha256(
            metadata.declaredGainDbi,
        )
        require(canonicalIdentity == record.normalizedContentSha256) {
            "The canonical antenna artifact does not match the verified project calculation identity."
        }
        return VerifiedCanonicalAntennaArtifact(
            pattern = canonical.pattern,
            metadata = metadata,
            formatVersion = formatVersion,
        )
    }

    private fun sha256(payload: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
