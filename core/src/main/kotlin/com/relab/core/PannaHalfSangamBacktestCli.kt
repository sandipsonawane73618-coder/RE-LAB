package com.relab.core

import java.io.File
import java.time.LocalDate

/**
 * Runs the production Panna/Half-Sangam/Sangam walk-forward evaluator over an
 * audited published-observation CSV. Targets are evaluated chronologically;
 * the evaluator itself uses only earlier same-market/session rows.
 */
object PannaHalfSangamBacktestCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "Usage: input-eligible.csv output-metrics.csv" }
        val input = File(args[0])
        val output = File(args[1])
        require(input.isFile) { "Eligible input CSV does not exist: $input" }

        val records = readRecords(input)
        require(records.isNotEmpty()) { "Eligible input CSV contains no chart records" }
        val marketsPresent = records.map { it.market }.toSet()
        require(marketsPresent == Market.entries.toSet()) {
            "Expected all nine markets; found: ${marketsPresent.map { it.name }.sorted()}"
        }

        val reports = PannaHalfSangamWalkForwardEvaluator.evaluate(
            records = records,
            targetDateExclusive = LocalDate.MAX,
            minimumHistory = 30,
            candidateLimit = 100,
            targetLimit = 365,
            halfSangamPannaLimit = 1000,
            halfSangamOppositeLimit = 1000,
            sangamPannaLimit = 1000
        )
        require(reports.map { it.market }.toSet() == Market.entries.toSet()) {
            "Walk-forward evaluation did not return all nine markets"
        }

        output.parentFile?.mkdirs()
        output.bufferedWriter().use { writer ->
            writer.appendLine(
                "market,session_id,metric,requested_targets,evaluated_targets,abstained_targets," +
                    "top10_hits,top10_rate,top100_hits,top100_rate,candidate_coverage,mrr," +
                    "rank1,rank2_to5,rank6_to10,rank11_to25,rank26_to50,rank51_to100,rank_above100"
            )
            reports.sortedWith(compareBy<PannaHalfSangamWalkForwardReport> { it.market.name }.thenBy { it.sessionId })
                .forEach { report ->
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA",
                        report.openPanna.requestedTargets, report.openPanna.evaluatedTargets,
                        report.openPanna.abstainedTargets, report.openPanna.exactTop10Hits,
                        report.openPanna.exactTop10Rate(), report.openPanna.exactTop100Hits,
                        report.openPanna.exactTop100Rate(), report.openPanna.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "CLOSE_PANNA",
                        report.closePanna.requestedTargets, report.closePanna.evaluatedTargets,
                        report.closePanna.abstainedTargets, report.closePanna.exactTop10Hits,
                        report.closePanna.exactTop10Rate(), report.closePanna.exactTop100Hits,
                        report.closePanna.exactTop100Rate(), report.closePanna.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA_CLOSE_ANK_PANNA1000_ANK1000",
                        report.openPannaCloseAnk.requestedTargets, report.openPannaCloseAnk.evaluatedTargets,
                        report.openPannaCloseAnk.abstainedTargets, report.openPannaCloseAnk.exactTop10Hits,
                        report.openPannaCloseAnk.exactTop10Rate(), report.openPannaCloseAnk.exactTop100Hits,
                        report.openPannaCloseAnk.exactTop100Rate(), report.openPannaCloseAnk.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA_CLOSE_ANK_CONDITIONED_ON_OPEN_PANNA",
                        report.openPannaCloseAnkConditioned.requestedTargets, report.openPannaCloseAnkConditioned.evaluatedTargets,
                        report.openPannaCloseAnkConditioned.abstainedTargets, report.openPannaCloseAnkConditioned.exactTop10Hits,
                        report.openPannaCloseAnkConditioned.exactTop10Rate(), report.openPannaCloseAnkConditioned.exactTop100Hits,
                        report.openPannaCloseAnkConditioned.exactTop100Rate(), report.openPannaCloseAnkConditioned.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_ANK_CLOSE_PANNA_PANNA1000_ANK1000",
                        report.openAnkClosePanna.requestedTargets, report.openAnkClosePanna.evaluatedTargets,
                        report.openAnkClosePanna.abstainedTargets, report.openAnkClosePanna.exactTop10Hits,
                        report.openAnkClosePanna.exactTop10Rate(), report.openAnkClosePanna.exactTop100Hits,
                        report.openAnkClosePanna.exactTop100Rate(), report.openAnkClosePanna.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_ANK_CLOSE_PANNA_CONDITIONED_ON_OPEN_ANK",
                        report.openAnkClosePannaConditioned.requestedTargets, report.openAnkClosePannaConditioned.evaluatedTargets,
                        report.openAnkClosePannaConditioned.abstainedTargets, report.openAnkClosePannaConditioned.exactTop10Hits,
                        report.openAnkClosePannaConditioned.exactTop10Rate(), report.openAnkClosePannaConditioned.exactTop100Hits,
                        report.openAnkClosePannaConditioned.exactTop100Rate(), report.openAnkClosePannaConditioned.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_PANNA_CLOSE_ANK_EXACT_FREQUENCY_BASELINE",
                        report.openPannaCloseAnkExactFrequencyBaseline.requestedTargets, report.openPannaCloseAnkExactFrequencyBaseline.evaluatedTargets,
                        report.openPannaCloseAnkExactFrequencyBaseline.abstainedTargets, report.openPannaCloseAnkExactFrequencyBaseline.exactTop10Hits,
                        report.openPannaCloseAnkExactFrequencyBaseline.exactTop10Rate(), report.openPannaCloseAnkExactFrequencyBaseline.exactTop100Hits,
                        report.openPannaCloseAnkExactFrequencyBaseline.exactTop100Rate(), report.openPannaCloseAnkExactFrequencyBaseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "OPEN_ANK_CLOSE_PANNA_EXACT_FREQUENCY_BASELINE",
                        report.openAnkClosePannaExactFrequencyBaseline.requestedTargets, report.openAnkClosePannaExactFrequencyBaseline.evaluatedTargets,
                        report.openAnkClosePannaExactFrequencyBaseline.abstainedTargets, report.openAnkClosePannaExactFrequencyBaseline.exactTop10Hits,
                        report.openAnkClosePannaExactFrequencyBaseline.exactTop10Rate(), report.openAnkClosePannaExactFrequencyBaseline.exactTop100Hits,
                        report.openAnkClosePannaExactFrequencyBaseline.exactTop100Rate(), report.openAnkClosePannaExactFrequencyBaseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "SANGAM_EXACT_FREQUENCY_BASELINE",
                        report.sangamExactFrequencyBaseline.requestedTargets, report.sangamExactFrequencyBaseline.evaluatedTargets,
                        report.sangamExactFrequencyBaseline.abstainedTargets, report.sangamExactFrequencyBaseline.exactTop10Hits,
                        report.sangamExactFrequencyBaseline.exactTop10Rate(), report.sangamExactFrequencyBaseline.exactTop100Hits,
                        report.sangamExactFrequencyBaseline.exactTop100Rate(), report.sangamExactFrequencyBaseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "SANGAM_CLOSE_PANNA_CONDITIONED_ON_OPEN_PANNA",
                        report.sangamClosePannaConditionedOnOpenPanna.requestedTargets, report.sangamClosePannaConditionedOnOpenPanna.evaluatedTargets,
                        report.sangamClosePannaConditionedOnOpenPanna.abstainedTargets, report.sangamClosePannaConditionedOnOpenPanna.exactTop10Hits,
                        report.sangamClosePannaConditionedOnOpenPanna.exactTop10Rate(), report.sangamClosePannaConditionedOnOpenPanna.exactTop100Hits,
                        report.sangamClosePannaConditionedOnOpenPanna.exactTop100Rate(), report.sangamClosePannaConditionedOnOpenPanna.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "SANGAM_CLOSE_PANNA_EXACT_FREQUENCY_BASELINE",
                        report.sangamClosePannaExactFrequencyBaseline.requestedTargets, report.sangamClosePannaExactFrequencyBaseline.evaluatedTargets,
                        report.sangamClosePannaExactFrequencyBaseline.abstainedTargets, report.sangamClosePannaExactFrequencyBaseline.exactTop10Hits,
                        report.sangamClosePannaExactFrequencyBaseline.exactTop10Rate(), report.sangamClosePannaExactFrequencyBaseline.exactTop100Hits,
                        report.sangamClosePannaExactFrequencyBaseline.exactTop100Rate(), report.sangamClosePannaExactFrequencyBaseline.exactRankDiagnostics)
                    appendMetric(writer, report.market, report.sessionId, "SANGAM_PANNA1000",
                        report.sangam.requestedTargets, report.sangam.evaluatedTargets,
                        report.sangam.abstainedTargets, report.sangam.exactTop10Hits,
                        report.sangam.exactTop10Rate(), report.sangam.exactTop100Hits,
                        report.sangam.exactTop100Rate(), report.sangam.exactRankDiagnostics)
                }
        }

        println("records=${records.size}; markets=${marketsPresent.size}; reports=${reports.size}; target_window=365_per_market_session; half_panna_window=1000; opposite_ank_window=1000; conditioned_open_to_close=enabled; conditioned_open_ank_to_close_panna=enabled; sangam_panna_window=1000; output=${output.absolutePath}")
    }

    private fun appendMetric(
        writer: java.io.BufferedWriter,
        market: Market,
        sessionId: String,
        metric: String,
        requested: Int,
        evaluated: Int,
        abstained: Int,
        top10Hits: Int,
        top10Rate: Double?,
        top100Hits: Int,
        top100Rate: Double?,
        ranks: ExactRankDiagnostics
    ) {
        fun number(value: Double?): String = value?.toString().orEmpty()
        writer.appendLine(
            listOf(
                market.name, sessionId, metric, requested, evaluated, abstained,
                top10Hits, number(top10Rate), top100Hits, number(top100Rate),
                number(ranks.candidatePoolCoverage()), number(ranks.meanReciprocalRank()),
                ranks.rank1, ranks.rank2To5, ranks.rank6To10, ranks.rank11To25,
                ranks.rank26To50, ranks.rank51To100, ranks.rankAbove100
            ).joinToString(",")
        )
    }

    private fun readRecords(file: File): List<ChartRecord> {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            val headerLine = reader.readLine() ?: error("CSV is empty")
            val header = parseCsvLine(headerLine)
            val columns = header.withIndex().associate { it.value.trim() to it.index }
            val required = listOf("market", "session_id", "date", "source_id", "status", "result")
            require(required.all(columns::containsKey)) { "CSV is missing required columns: $required" }

            fun field(values: List<String>, name: String): String =
                values.getOrNull(columns.getValue(name)).orEmpty().trim()

            val output = mutableListOf<ChartRecord>()
            reader.lineSequence().filter { it.isNotBlank() }.forEachIndexed { index, line ->
                val values = parseCsvLine(line)
                if (!field(values, "status").equals("PUBLISHED", ignoreCase = true)) return@forEachIndexed
                val parts = field(values, "result").split('-')
                require(parts.size == 3) { "Invalid result at CSV data row ${index + 2}" }
                val market = runCatching { Market.valueOf(field(values, "market").uppercase()) }
                    .getOrElse { throw IllegalArgumentException("Unknown market at CSV data row ${index + 2}") }
                val record = ChartRecord(
                    market = market,
                    date = LocalDate.parse(field(values, "date")),
                    openPanna = parts[0],
                    jodi = parts[1],
                    closePanna = parts[2],
                    sourceId = field(values, "source_id"),
                    sourceRecordId = columns["source_record_id"]?.let { values.getOrNull(it) },
                    sessionId = field(values, "session_id")
                )
                val validation = ChartMath.validate(record)
                require(validation is ValidationResult.Valid) {
                    "Invalid Panna/Jodi math at CSV data row ${index + 2}: ${(validation as ValidationResult.Invalid).reasons}"
                }
                output += record
            }
            return output
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                char == '"' -> quoted = !quoted
                char == ',' && !quoted -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        require(!quoted) { "Malformed CSV quoting" }
        fields += current.toString()
        return fields
    }
}
