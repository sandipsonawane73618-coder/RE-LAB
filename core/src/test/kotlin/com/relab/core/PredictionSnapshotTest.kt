package com.relab.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PredictionSnapshotTest {
    private val target = LocalDate.parse("2026-11-21")
    private val created = Instant.parse("2026-11-20T18:00:00Z")

    @Test
    fun snapshotHasStableKeyAndPayloadHash() {
        val snapshot = PredictionSnapshot(
            Market.KALYAN, "main", target, created,
            LocalDate.parse("2026-11-20"), "reverse-v1",
            """{"open":["123","456"],"jodi":["12"],"close":["789"]}"""
        )

        assertEquals("KALYAN|main|2026-11-21", snapshot.snapshotKey)
        assertEquals(PredictionSnapshot.sha256(snapshot.payloadJson), snapshot.payloadSha256)
        assertEquals(64, snapshot.payloadSha256.length)
    }

    @Test
    fun rejectsHistoryThatExtendsBeyondTargetDate() {
        assertThrows(IllegalArgumentException::class.java) {
            PredictionSnapshot(
                Market.KALYAN, "main", target, created,
                target.plusDays(1), "reverse-v1", """{"open":["123"]}"""
            )
        }
    }

    @Test
    fun rejectsTamperedPayloadHash() {
        assertThrows(IllegalArgumentException::class.java) {
            PredictionSnapshot(
                Market.KALYAN, "main", target, created,
                target.minusDays(1), "reverse-v1", """{"open":["123"]}""",
                "0".repeat(64)
            )
        }
    }

    @Test
    fun outcomeIsSeparateAndDoesNotRewriteSnapshot() {
        val snapshot = PredictionSnapshot(
            Market.SRIDEVI, "day", target, created,
            target.minusDays(1), "reverse-v1", """{"open":["123"]}"""
        )
        val outcome = PredictionSnapshotOutcome(
            snapshot.snapshotKey, "123-45-678",
            Instant.parse("2026-11-21T12:00:00Z"), "PUBLISHED"
        )

        assertEquals(snapshot.payloadJson, """{"open":["123"]}""")
        assertEquals(snapshot.payloadSha256, PredictionSnapshot.sha256(snapshot.payloadJson))
        assertEquals(snapshot.snapshotKey, outcome.snapshotKey)
        assertFalse(outcome.actualResult.isBlank())
        assertTrue(outcome.publishedAt.isAfter(snapshot.createdAt))
    }
}
