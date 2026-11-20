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
}
