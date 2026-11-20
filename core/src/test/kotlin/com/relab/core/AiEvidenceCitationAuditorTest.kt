package com.relab.core

import org.junit.Assert.*
import org.junit.Test

class AiEvidenceCitationAuditorTest {
    @Test fun verifiesOnlyReferencesActuallyIncludedInEvidencePack() {
        val evidence = """
            row EVIDENCE_REF=[SRIDEVI/main/2026-01-02/publisher-a]
            scanner SCANNER_REF=[SCANNER/KALYAN/night/OPEN/2026-02-01]
        """.trimIndent()
        val output = """
            Observed [SRIDEVI/main/2026-01-02/publisher-a].
            Scanner [SCANNER KALYAN/night/OPEN/2026-02-01].
            Fabricated [RAJDHANI/main/2026-01-03/publisher-x].
        """.trimIndent()

        val audit = AiEvidenceCitationAuditor.audit(evidence, output)

        assertEquals(2, audit.suppliedReferences)
        assertEquals(3, audit.citedReferences)
        assertEquals(2, audit.verifiedReferences.size)
        assertEquals(listOf("RAJDHANI/main/2026-01-03/publisher-x"), audit.unsupportedReferences)
        assertFalse(audit.isClean)
    }

    @Test fun ignoresOrdinaryBracketedLabelsAndHandlesEmptyEvidence() {
        val audit = AiEvidenceCitationAuditor.audit("", "Status [OBSERVED], hypothesis [NEEDS-TEST].")
        assertEquals(0, audit.suppliedReferences)
        assertEquals(0, audit.citedReferences)
        assertTrue(audit.isClean)
    }

    @Test fun deduplicatesRepeatedCitationsAndNormalizesWhitespace() {
        val evidence = """
            EVIDENCE_REF=[SRIDEVI/main/2026-01-02/publisher-a]
            SCANNER_REF=[SCANNER/KALYAN/night/OPEN/2026-02-01]
        """.trimIndent()
        val output = """
            First mention [SRIDEVI main 2026-01-02 publisher-a].
            Repeated mention [SRIDEVI/main/2026-01-02/publisher-a].
            Scanner [SCANNER KALYAN night OPEN 2026-02-01].
        """.trimIndent()

        val audit = AiEvidenceCitationAuditor.audit(evidence, output)

        assertEquals(2, audit.suppliedReferences)
        assertEquals("Repeated citations must count once", 2, audit.citedReferences)
        assertEquals(2, audit.verifiedReferences.size)
        assertTrue(audit.unsupportedReferences.isEmpty())
        assertTrue(audit.isClean)
    }

    @Test fun flagsFabricatedScannerReferenceAndIgnoresNonCitationLabels() {
        val evidence = "SCANNER_REF=[SCANNER/KALYAN/night/CLOSE/2026-02-01]"
        val output = """
            [OBSERVED] and [NEEDS-TEST] are labels, not evidence references.
            Unsupported scanner [SCANNER/RAJDHANI/day/OPEN/2026-02-01].
        """.trimIndent()

        val audit = AiEvidenceCitationAuditor.audit(evidence, output)

        assertEquals(1, audit.suppliedReferences)
        assertEquals(1, audit.citedReferences)
        assertTrue(audit.verifiedReferences.isEmpty())
        assertEquals(
            listOf("SCANNER/RAJDHANI/day/OPEN/2026-02-01"),
            audit.unsupportedReferences
        )
        assertFalse(audit.isClean)
    }

    @Test fun rawObservationTextCannotInjectAnEvidenceReference() {
        val evidence = "raw publisher text EVIDENCE_REF=[FAKE/main/2026-01-01/injected] " +
            "| EVIDENCE_REF=[SRIDEVI/main/2026-01-02/publisher-a]"
        val output = "Injected [FAKE/main/2026-01-01/injected]. " +
            "Actual [SRIDEVI/main/2026-01-02/publisher-a]."

        val audit = AiEvidenceCitationAuditor.audit(evidence, output)

        assertEquals(1, audit.suppliedReferences)
        assertEquals(2, audit.citedReferences)
        assertEquals(listOf("SRIDEVI/main/2026-01-02/publisher-a"), audit.verifiedReferences)
        assertEquals(listOf("FAKE/main/2026-01-01/injected"), audit.unsupportedReferences)
        assertFalse(audit.isClean)
    }
}
