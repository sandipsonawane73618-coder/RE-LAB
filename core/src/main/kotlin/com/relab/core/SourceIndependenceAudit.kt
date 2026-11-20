package com.relab.core

/**
 * Pairwise comparison of independent publishers for one market/session.
 * Dates with multiple different values from the same publisher are marked
 * ambiguous and excluded from the agreement-rate denominator.
 */
data class PublisherPairAgreement(
    val market: Market,
    val sessionId: String,
    val firstPublisherId: String,
    val secondPublisherId: String,
    val sharedPublishedDates: Int,
    val agreedDates: Int,
    val conflictingDates: Int,
    val ambiguousDates: Int
) {
    val comparableDates: Int get() = agreedDates + conflictingDates
    val agreementRate: Double?
        get() = if (comparableDates == 0) null else agreedDates.toDouble() / comparableDates
}

/** Source-independence and corroboration coverage for one market/session. */
data class SourceIndependenceReport(
    val market: Market,
    val sessionId: String,
    val publisherIds: List<String>,
    val publishedDates: Int,
    val multiPublisherDates: Int,
    val consensusDates: Int,
    val singlePublisherDates: Int,
    val conflictDates: Int,
    val noPublishedResultDates: Int,
    val pairAgreements: List<PublisherPairAgreement>
) {
    val independentPublisherCount: Int get() = publisherIds.size
}

/**
 * Audits independent publisher coverage without treating multiple pages,
 * snapshots, or changed values from one publisher as independent evidence.
 */
object SourceIndependenceAudit {
    fun summarize(observations: List<SourceObservation>): List<SourceIndependenceReport> {
        return observations.groupBy { it.market to it.sessionId }
            .entries
            .sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
            .map { (identity, rows) ->
                val groups = SourceReconciliation.reconcile(rows)
                val published = rows.filter {
                    it.status == ObservationStatus.PUBLISHED && !it.normalizedResult.isNullOrBlank()
                }
                val publisherIds = published.map { it.sourceId }.distinct().sorted()
                val pairReports = mutableListOf<PublisherPairAgreement>()

                for (firstIndex in publisherIds.indices) {
                    for (secondIndex in firstIndex + 1 until publisherIds.size) {
                        val firstId = publisherIds[firstIndex]
                        val secondId = publisherIds[secondIndex]
                        val firstByDate = published.filter { it.sourceId == firstId }
                            .groupBy { it.date }
                            .mapValues { (_, dateRows) -> dateRows.mapNotNull { it.normalizedResult }.distinct().sorted() }
                        val secondByDate = published.filter { it.sourceId == secondId }
                            .groupBy { it.date }
                            .mapValues { (_, dateRows) -> dateRows.mapNotNull { it.normalizedResult }.distinct().sorted() }
                        val sharedDates = firstByDate.keys.intersect(secondByDate.keys).sorted()
                        var agreed = 0
                        var conflicting = 0
                        var ambiguous = 0
                        sharedDates.forEach { date ->
                            val firstValues = firstByDate.getValue(date)
                            val secondValues = secondByDate.getValue(date)
                            if (firstValues.size != 1 || secondValues.size != 1) {
                                ambiguous++
                            } else if (firstValues.single() == secondValues.single()) {
                                agreed++
                            } else {
                                conflicting++
                            }
                        }
                        if (sharedDates.isNotEmpty()) {
                            pairReports += PublisherPairAgreement(
                                identity.first, identity.second, firstId, secondId,
                                sharedDates.size, agreed, conflicting, ambiguous
                            )
                        }
                    }
                }

                SourceIndependenceReport(
                    market = identity.first,
                    sessionId = identity.second,
                    publisherIds = publisherIds,
                    publishedDates = groups.count { group ->
                        group.observations.any { it.status == ObservationStatus.PUBLISHED }
                    },
                    multiPublisherDates = groups.count { group ->
                        group.observations.filter { it.status == ObservationStatus.PUBLISHED }
                            .map { it.sourceId }.distinct().size >= 2
                    },
                    consensusDates = groups.count { it.state == ReconciliationState.CONSENSUS },
                    singlePublisherDates = groups.count { it.state == ReconciliationState.SINGLE_SOURCE_UNVERIFIED },
                    conflictDates = groups.count { it.state == ReconciliationState.CONFLICT },
                    noPublishedResultDates = groups.count { it.state == ReconciliationState.NO_PUBLISHED_RESULT },
                    pairAgreements = pairReports.sortedWith(
                        compareBy<PublisherPairAgreement> { it.firstPublisherId }
                            .thenBy { it.secondPublisherId }
                    )
                )
            }
    }
}
