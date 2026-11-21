package com.relab.core

/**
 * Descriptive source-quality indicators for one market/session/publisher.
 *
 * The score is a weighted, renormalized index of observable data-quality signals.
 * It is not a probability that a result is true and must not be used as a prediction
 * probability. Corroboration measures agreement with other publisher IDs only.
 */
enum class SourceEvidenceLevel { LOW_SAMPLE, LIMITED_SAMPLE, LARGER_SAMPLE }

data class SourceReliabilityRecord(
    val market: Market,
    val sessionId: String,
    val sourceId: String,
    val eligibleObservations: Int,
    val publishedObservations: Int,
    val missingObservations: Int,
    val malformedObservations: Int,
    val publicationCompletenessRate: Double?,
    val publicationTimestampCoverageRate: Double?,
    val timelyPublicationRate: Double?,
    val independentCorroborationRate: Double?,
    val corroborationComparableDates: Int,
    val score: Double?,
    val includedComponents: Int,
    val evidenceLevel: SourceEvidenceLevel
)

object SourceReliabilityScore {
    private data class WeightedSignal(val value: Double, val weight: Double)

    /**
     * PENDING and HOLIDAY_CLOSED rows are excluded from the completeness denominator.
     * Negative publication-to-retrieval lags count as not timely. Missing timestamps
     * are reported separately and are not silently replaced with retrieval timestamps.
     */
    fun summarize(
        observations: List<SourceObservation>,
        maximumPublicationLagMinutes: Long = 180
    ): List<SourceReliabilityRecord> {
        require(maximumPublicationLagMinutes >= 0) {
            "Maximum publication lag must not be negative"
        }
        if (observations.isEmpty()) return emptyList()

        val independence = SourceIndependenceAudit.summarize(observations)
        val pairByIdentity = independence.associateBy { it.market to it.sessionId }

        return observations
            .groupBy { Triple(it.market, it.sessionId, it.sourceId) }
            .map { (identity, rows) ->
                val eligible = rows.filter {
                    it.status == ObservationStatus.PUBLISHED ||
                        it.status == ObservationStatus.MISSING_FROM_SOURCE ||
                        it.status == ObservationStatus.MALFORMED
                }
                val published = rows.filter { it.status == ObservationStatus.PUBLISHED }
                val timestamped = published.mapNotNull { row ->
                    row.publishedAt?.let { publishedAt ->
                        java.time.Duration.between(publishedAt, row.retrievedAt).toMinutes()
                    }
                }
                val completeness = eligible.takeIf { it.isNotEmpty() }?.let {
                    published.size.toDouble() / it.size
                }
                val timestampCoverage = published.takeIf { it.isNotEmpty() }?.let {
                    timestamped.size.toDouble() / it.size
                }
                val timelyRate = timestamped.takeIf { it.isNotEmpty() }?.let { lags ->
                    lags.count { it >= 0 && it <= maximumPublicationLagMinutes }.toDouble() / lags.size
                }

                val pairReports = pairByIdentity[identity.first to identity.second]?.pairAgreements.orEmpty()
                val relevantPairs = pairReports.filter {
                    it.firstPublisherId == identity.third || it.secondPublisherId == identity.third
                }
                val comparable = relevantPairs.sumOf { it.comparableDates }
                val corroboration = if (comparable == 0) null else
                    relevantPairs.sumOf { it.agreedDates }.toDouble() / comparable

                val signals = listOfNotNull(
                    completeness?.let { WeightedSignal(it, 0.40) },
                    timestampCoverage?.let { WeightedSignal(it, 0.15) },
                    timelyRate?.let { WeightedSignal(it, 0.20) },
                    corroboration?.let { WeightedSignal(it, 0.25) }
                )
                val totalWeight = signals.sumOf { it.weight }
                val score = if (signals.isEmpty()) null else
                    signals.sumOf { it.value * it.weight } / totalWeight * 100.0

                SourceReliabilityRecord(
                    market = identity.first,
                    sessionId = identity.second,
                    sourceId = identity.third,
                    eligibleObservations = eligible.size,
                    publishedObservations = published.size,
                    missingObservations = rows.count { it.status == ObservationStatus.MISSING_FROM_SOURCE },
                    malformedObservations = rows.count { it.status == ObservationStatus.MALFORMED },
                    publicationCompletenessRate = completeness,
                    publicationTimestampCoverageRate = timestampCoverage,
                    timelyPublicationRate = timelyRate,
                    independentCorroborationRate = corroboration,
                    corroborationComparableDates = comparable,
                    score = score,
                    includedComponents = signals.size,
                    evidenceLevel = when {
                        eligible.size < 10 -> SourceEvidenceLevel.LOW_SAMPLE
                        eligible.size < 30 -> SourceEvidenceLevel.LIMITED_SAMPLE
                        else -> SourceEvidenceLevel.LARGER_SAMPLE
                    }
                )
            }
            .sortedWith(compareBy<SourceReliabilityRecord> { it.market.name }
                .thenBy { it.sessionId }.thenBy { it.sourceId })
    }
}
