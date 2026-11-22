package com.relab.core

enum class DuplicateChartDayState {
    SAME_RESULT_MULTIPLE_ROWS,
    CONFLICTING_RESULTS
}

data class DuplicateChartDayGroup(
    val market: Market,
    val sessionId: String,
    val date: java.time.LocalDate,
    val records: List<ChartRecord>,
    val state: DuplicateChartDayState
)

data class ChartTimelineCoverage(
    val market: Market,
    val sessionId: String,
    val firstDate: java.time.LocalDate,
    val lastDate: java.time.LocalDate,
    val distinctDates: Int
)

data class ObservationTimeIssue(
    val market: Market,
    val sessionId: String,
    val date: java.time.LocalDate,
    val sourceId: String,
    val publishedAt: java.time.Instant,
    val retrievedAt: java.time.Instant
)

data class ChartDataQualityReport(
    val chartRecordCount: Int,
    val validChartRecordCount: Int,
    val invalidChartRecords: List<Pair<ChartRecord, List<String>>>,
    val duplicateChartDays: List<DuplicateChartDayGroup>,
    val timelineCoverage: List<ChartTimelineCoverage>,
    val observationCountsByStatus: Map<ObservationStatus, Int>,
    val invalidPublishedObservations: List<SourceObservation>,
    val publicationAfterRetrieval: List<ObservationTimeIssue>,
    val repeatedSourceRecordGroups: List<DuplicateObservationGroup>,
    val repeatedPublishedResultReviewGroups: List<RepeatedPublishedResultGroup>
) {
    val hasBlockingIssues: Boolean
        get() = invalidChartRecords.isNotEmpty() ||
            duplicateChartDays.any { it.state == DuplicateChartDayState.CONFLICTING_RESULTS } ||
            invalidPublishedObservations.isNotEmpty() ||
            publicationAfterRetrieval.isNotEmpty()

    val reviewIssueCount: Int
        get() = duplicateChartDays.size +
            repeatedSourceRecordGroups.size +
            repeatedPublishedResultReviewGroups.size
}

/**
 * Non-destructive audit for chart history and source observations.
 * It reports suspicious data; it never deletes, repairs, or silently chooses a result.
 * Calendar gaps are deliberately not inferred because market schedules can change historically.
 */
object ChartDataQualityAuditor {
    fun audit(
        records: List<ChartRecord>,
        observations: List<SourceObservation>,
        repeatedResultReviewThreshold: Int = 3
    ): ChartDataQualityReport {
        require(repeatedResultReviewThreshold >= 2) {
            "repeatedResultReviewThreshold must be at least 2"
        }

        val invalidRecords = records.mapNotNull { record ->
            when (val validation = ChartMath.validate(record)) {
                is ValidationResult.Valid -> null
                is ValidationResult.Invalid -> record to validation.reasons
            }
        }

        val duplicateDays = records
            .groupBy { Triple(it.market, it.sessionId, it.date) }
            .values
            .filter { it.size > 1 }
            .map { group ->
                val first = group.first()
                val distinctResults = group.map {
                    "${it.openPanna}-${it.jodi}-${it.closePanna}"
                }.distinct()
                DuplicateChartDayGroup(
                    market = first.market,
                    sessionId = first.sessionId,
                    date = first.date,
                    records = group.sortedWith(compareBy<ChartRecord> { it.sourceId }
                        .thenBy { it.sourceRecordId ?: "" }),
                    state = if (distinctResults.size > 1) {
                        DuplicateChartDayState.CONFLICTING_RESULTS
                    } else {
                        DuplicateChartDayState.SAME_RESULT_MULTIPLE_ROWS
                    }
                )
            }
            .sortedWith(compareBy<DuplicateChartDayGroup> { it.market.name }
                .thenBy { it.sessionId }.thenBy { it.date })

        val coverage = records
            .groupBy { it.market to it.sessionId }
            .map { (key, group) ->
                val dates = group.map { it.date }.distinct().sorted()
                ChartTimelineCoverage(
                    market = key.first,
                    sessionId = key.second,
                    firstDate = dates.first(),
                    lastDate = dates.last(),
                    distinctDates = dates.size
                )
            }
            .sortedWith(compareBy<ChartTimelineCoverage> { it.market.name }.thenBy { it.sessionId })

        val invalidPublished = observations.filter { observation ->
            if (observation.status != ObservationStatus.PUBLISHED) {
                false
            } else {
                val parts = observation.normalizedResult.orEmpty().split('-')
                if (parts.size != 3 || parts[0].length != 3 || parts[1].length != 2 ||
                    parts[2].length != 3 || parts.any { part -> !part.all(Char::isDigit) }) {
                    true
                } else {
                    ChartMath.validate(
                        ChartRecord(
                            market = observation.market,
                            date = observation.date,
                            openPanna = parts[0],
                            jodi = parts[1],
                            closePanna = parts[2],
                            sourceId = observation.sourceId,
                            sourceRecordId = observation.sourceRecordId,
                            publishedAt = observation.publishedAt,
                            observedAt = observation.retrievedAt,
                            sessionId = observation.sessionId
                        )
                    ) !is ValidationResult.Valid
                }
            }
        }

        val timeIssues = observations.mapNotNull { observation ->
            val publishedAt = observation.publishedAt ?: return@mapNotNull null
            if (publishedAt.isAfter(observation.retrievedAt)) {
                ObservationTimeIssue(
                    observation.market, observation.sessionId, observation.date,
                    observation.sourceId, publishedAt, observation.retrievedAt
                )
            } else null
        }.sortedWith(compareBy<ObservationTimeIssue> { it.market.name }
            .thenBy { it.sessionId }.thenBy { it.date }.thenBy { it.sourceId })

        return ChartDataQualityReport(
            chartRecordCount = records.size,
            validChartRecordCount = records.size - invalidRecords.size,
            invalidChartRecords = invalidRecords,
            duplicateChartDays = duplicateDays,
            timelineCoverage = coverage,
            observationCountsByStatus = ObservationStatus.entries.associateWith { status ->
                observations.count { it.status == status }
            },
            invalidPublishedObservations = invalidPublished,
            publicationAfterRetrieval = timeIssues,
            repeatedSourceRecordGroups = SourceObservationMerger.auditRepeatedSourceRecords(observations),
            repeatedPublishedResultReviewGroups =
                SourceObservationMerger.auditRepeatedPublishedResultsAcrossDates(
                    observations, repeatedResultReviewThreshold
                )
        )
    }
}
