package com.relab.core

import java.time.LocalDate

enum class ChartPatternSide { OPEN_PANNA, CLOSE_PANNA }

enum class PatternDirection(val rowDelta: Int, val columnDelta: Int) {
    HORIZONTAL(0, 1),
    HORIZONTAL_REVERSE(0, -1),
    VERTICAL(1, 0),
    VERTICAL_REVERSE(-1, 0),
    DIAGONAL_DOWN_RIGHT(1, 1),
    DIAGONAL_UP_LEFT(-1, -1),
    DIAGONAL_DOWN_LEFT(1, -1),
    DIAGONAL_UP_RIGHT(-1, 1)
}

data class PatternGridCoordinate(val date: LocalDate, val position: Int)

data class DirectionalPatternOccurrence(
    val pattern: String,
    val direction: PatternDirection,
    val step: Int,
    val start: PatternGridCoordinate,
    val cells: List<PatternGridCoordinate>,
    val sourceIds: List<String>
)

data class DirectionalPatternReport(
    val market: Market,
    val sessionId: String,
    val side: ChartPatternSide,
    val acceptedRecords: Int,
    val rejectedRecords: Int,
    val occurrences: List<DirectionalPatternOccurrence>
)

/**
 * Scans the three digit positions of each daily Panna as a two-dimensional chart:
 * rows are calendar dates and columns are Panna digit positions (hundreds/tens/units).
 *
 * A stride greater than one is retained as explicit evidence (step=2 means one
 * intervening row/column is skipped); missing cells are never silently filled.
 * This is descriptive pattern discovery, not a prediction or probability claim.
 */
object ChartPatternGridScanner {
    private data class Cell(val value: Char, val sourceId: String)

    fun scan(
        records: List<ChartRecord>,
        side: ChartPatternSide,
        minimumLength: Int = 3,
        maximumStep: Int = 2
    ): List<DirectionalPatternReport> {
        require(minimumLength >= 2) { "minimumLength must be at least 2" }
        require(maximumStep >= 1) { "maximumStep must be at least 1" }

        return records.groupBy { it.market to it.sessionId }
            .entries
            .sortedWith(compareBy({ it.key.first.name }, { it.key.second }))
            .map { (identity, rows) ->
                val valid = rows.mapNotNull { record ->
                    val features = ChartMath.validate(record) as? ValidationResult.Valid ?: return@mapNotNull null
                    val panna = when (side) {
                        ChartPatternSide.OPEN_PANNA -> record.openPanna
                        ChartPatternSide.CLOSE_PANNA -> record.closePanna
                    }
                    record to features.features.let { panna }
                }.sortedBy { it.first.date }

                require(valid.map { it.first.date }.distinct().size == valid.size) {
                    "Duplicate dates for ${identity.first}/${identity.second}: reconcile source observations before scanning"
                }

                val cells = mutableMapOf<PatternGridCoordinate, Cell>()
                valid.forEach { (record, panna) ->
                    panna.forEachIndexed { position, digit ->
                        cells[PatternGridCoordinate(record.date, position)] = Cell(digit, record.sourceId)
                    }
                }

                val occurrences = mutableListOf<DirectionalPatternOccurrence>()
                val directions = PatternDirection.entries
                for ((coordinate, _) in cells) {
                    for (direction in directions) {
                        for (step in 1..maximumStep) {
                            val predecessor = PatternGridCoordinate(
                                coordinate.date.minusDays(direction.rowDelta.toLong() * step),
                                coordinate.position - direction.columnDelta * step
                            )
                            if (predecessor in cells) continue

                            val path = mutableListOf<PatternGridCoordinate>()
                            val values = StringBuilder()
                            val sourceIds = mutableListOf<String>()
                            var cursor = coordinate
                            while (true) {
                                val cell = cells[cursor] ?: break
                                path += cursor
                                values.append(cell.value)
                                sourceIds += cell.sourceId
                                cursor = PatternGridCoordinate(
                                    cursor.date.plusDays(direction.rowDelta.toLong() * step),
                                    cursor.position + direction.columnDelta * step
                                )
                            }
                            if (path.size >= minimumLength) {
                                occurrences += DirectionalPatternOccurrence(
                                    pattern = values.toString(),
                                    direction = direction,
                                    step = step,
                                    start = coordinate,
                                    cells = path.toList(),
                                    sourceIds = sourceIds.toList()
                                )
                            }
                        }
                    }
                }

                DirectionalPatternReport(
                    market = identity.first,
                    sessionId = identity.second,
                    side = side,
                    acceptedRecords = valid.size,
                    rejectedRecords = rows.size - valid.size,
                    occurrences = occurrences.distinct().sortedWith(
                        compareBy<DirectionalPatternOccurrence> { it.direction.name }
                            .thenBy { it.start.date }
                            .thenBy { it.start.position }
                            .thenBy { it.step }
                            .thenBy { it.pattern }
                    )
                )
            }
    }
}
