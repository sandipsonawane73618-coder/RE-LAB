package com.relab.core

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/**
 * Immutable prediction record captured before a target result is published.
 *
 * payloadJson contains the complete ranked prediction payload (all channels and
 * evidence scores) as produced at prediction time. It must never be regenerated
 * from current chart history during an outcome audit.
 */
data class PredictionSnapshot(
    val market: Market,
    val sessionId: String,
    val targetDate: LocalDate,
    val createdAt: Instant,
    val historyThroughDate: LocalDate,
    val modelVersion: String,
    val payloadJson: String,
    val payloadSha256: String = sha256(payloadJson)
) {
    init {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        require(modelVersion.isNotBlank()) { "Model version must not be blank" }
        require(payloadJson.isNotBlank()) { "Prediction payload must not be blank" }
        require(!historyThroughDate.isAfter(targetDate)) {
            "Prediction history cannot include dates after the target date"
        }
        require(payloadSha256 == sha256(payloadJson)) {
            "Prediction payload hash does not match its contents"
        }
    }

    /** Stable natural key: a target can have one first-write snapshot per market/session. */
    val snapshotKey: String
        get() = "${market.name}|$sessionId|$targetDate"

    companion object {
        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Actual outcomes are separate from the frozen prediction. Attaching a result
 * never mutates the prediction payload or its hash.
 */
data class PredictionSnapshotOutcome(
    val snapshotKey: String,
    val actualResult: String,
    val publishedAt: Instant,
    val sourceStatus: String
) {
    init {
        require(snapshotKey.isNotBlank())
        require(actualResult.isNotBlank())
        require(sourceStatus.isNotBlank())
    }
}
