package com.relab.core

data class AiCitationAudit(
    val suppliedReferences: Int,
    val citedReferences: Int,
    val verifiedReferences: List<String>,
    val unsupportedReferences: List<String>
) {
    val isClean: Boolean get() = unsupportedReferences.isEmpty()
}

/**
 * Deterministically checks citations emitted by an AI against the exact reference tokens
 * present in the evidence pack. A verified token proves traceability only, not truth.
 */
object AiEvidenceCitationAuditor {
    private val suppliedRef = Regex("""(?:^|\s)(?:EVIDENCE_REF|SCANNER_REF)=\[([^\]\r\n]+)]\s*$""")
    private val citation = Regex("""\[([^\]]+)]""")
    private val marketPrefixes = Market.values().map { it.name + "/" }

    fun audit(evidence: String, aiOutput: String): AiCitationAudit {
        val supplied = evidence.lineSequence()
            .mapNotNull { line -> suppliedRef.find(line)?.groupValues?.get(1) }
            .map(::normalize)
            .toSet()
        val cited = citation.findAll(aiOutput).map { normalize(it.groupValues[1]) }
            .filter { token -> token.startsWith("SCANNER/") || marketPrefixes.any(token::startsWith) }
            .distinct()
            .toList()
        val verified = cited.filter { it in supplied }
        val unsupported = cited.filterNot { it in supplied }
        return AiCitationAudit(supplied.size, cited.size, verified, unsupported)
    }

    private fun normalize(value: String): String =
        value.trim().replace(Regex("""\s+"""), "/")
}
