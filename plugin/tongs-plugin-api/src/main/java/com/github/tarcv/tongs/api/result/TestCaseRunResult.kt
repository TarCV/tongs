/*
 * Copyright 2020 TarCV
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License.
 *
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.github.tarcv.tongs.api.result

import com.github.tarcv.tongs.api.devices.Device
import com.github.tarcv.tongs.api.devices.Pool
import com.github.tarcv.tongs.api.devices.Pool.Builder.aDevicePool
import com.github.tarcv.tongs.api.testcases.TestCase
import com.github.tarcv.tongs.api.result.Table.Companion.tableFromFile
import com.github.tarcv.tongs.api.run.ResultStatus
import com.github.tarcv.tongs.api.run.TestCaseEvent.Companion.TEST_TYPE_TAG
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

sealed class RunTesult

/**
 * Request next compatibe runner to execute a test case
 */
class Delegate: RunTesult()

// TODO: merge with com.github.tarcv.tongs.summary.TestResult
data class TestCaseRunResult(
        val pool: Pool,
        val device: Device,
        val testCase: TestCase,

        // TODO: Split result to a different class (sealed class hierarchy)
        val status: ResultStatus,
        val stackTraces: List<StackTrace>,
        val startTimestampUtc: Instant,
        val endTimestampUtc: Instant = Instant.EPOCH,
        val netStartTimestampUtc: Instant?,
        val netEndTimestampUtc: Instant?,
        private val baseTotalFailureCount: Int,
        val additionalProperties: Map<String, String>,
        val coverageReport: TestCaseFile? = null,
        val data: List<TestReportData>
): RunTesult() {
    val totalFailureCount: Int
        get() {
            val increment = when(status) {
                ResultStatus.PASS, ResultStatus.IGNORED, ResultStatus.ASSUMPTION_FAILED -> 0
                ResultStatus.FAIL, ResultStatus.ERROR -> 1
            }
            return baseTotalFailureCount + increment
        }
    val timeTaken: Duration
        get() {
            val endInstant = endTimestampUtc
            return if (endInstant == Instant.EPOCH) {
                throw IllegalStateException("Can't check timeTaken before the test case finishes execution")
            } else {
                Duration.between(startTimestampUtc, endInstant)
            }
        }

    val timeNetTaken: Duration?
        get() {
            val startInstant = netStartTimestampUtc
            val endInstant = netEndTimestampUtc
            return if (startInstant == null || endInstant == null) {
                null
            } else {
                Duration.between(startInstant, endInstant)
            }
        }

    val timeTakenMillis: Long
        get() = timeTaken.toMillis()
    val timeNetTakenMillis: Long?
        get() = timeNetTaken?.toMillis()

    val timeTakenSeconds: Float
        get() = timeTakenMillis / 1000f
    val timeNetTakenSeconds: Float?
        get() = timeNetTakenMillis?.div(1000f)

    companion object {
        private val pool = aDevicePool().addDevice(Device.TEST_DEVICE).build()
        @JvmField val NO_TRACE = listOf(StackTrace("", "", ""))

        @JvmStatic
        fun aTestResult(testClass: String, testMethod: String, status: ResultStatus, traces: List<StackTrace>): TestCaseRunResult {
            return aTestResult(pool, Device.TEST_DEVICE, testClass, testMethod, status, traces)
        }

        @JvmStatic
        @JvmOverloads
        fun aTestResult(
                pool: Pool,
                device: Device,
                testClass: String,
                testMethod: String,
                status: ResultStatus,
                traces: List<StackTrace>,
                baseTotalFailureCount: Int = 0
        ): TestCaseRunResult {
            return TestCaseRunResult(pool, device, TestCase(TEST_TYPE_TAG, testMethod, testClass), status, traces,
                    Instant.now(), Instant.now().plusMillis(15), Instant.now(), Instant.now().plusMillis(15),
                    baseTotalFailureCount, emptyMap(), null, emptyList())
        }
    }
}

data class StackTrace(
        /**
         * Should be the class type of an exception for compatible languages (and just the type of an error otherwise)
         */
        val errorType: String,

        /**
         * Any additional information besides [errorType] and a stack trace
         */
        val errorMessage: String,

        /**
         * Should include both [errorType] and [errorMessage] in some form in the first line
         */
        val fullTrace: String
)

class TestCaseFile(
        val fileManager: TestCaseFileManager,
        val fileType: FileType,
        val suffix: String
) {
    val relativePath: String
        get() = fileManager.getRelativeFile(fileType, suffix).path

    fun create(): File {
        return fileManager.createFile(fileType, suffix)
    }

    fun toFile(): File {
        return fileManager.getFile(fileType, suffix)
    }
}

/**
 * All child classes must have some uniqely named field that is not present in other child classes
 * (so that they can be distinguished in Mustache templates)
 */
sealed class TestReportData(
    val title: String
)

interface MonoTextReportData {
    val title: String
    val type: SimpleMonoTextReportData.Type
    val monoText: String
}
class SimpleMonoTextReportData(title: String, override val type: Type, override val monoText: String)
    : TestReportData(title), MonoTextReportData {
    enum class Type {
        STDOUT,
        STRERR,
        OTHER
    }
}
class FileMonoTextReportData(
        title: String,
        override val type: SimpleMonoTextReportData.Type,
        private val monoTextPath: TestCaseFile
): TestReportData(title), MonoTextReportData {
    override val monoText: String
        get() {
            return monoTextPath.toFile()
                    .readText(StandardCharsets.UTF_8)
        }
}

interface HtmlReportData {
    val title: String
    val html: String
}
class SimpleHtmlReportData(title: String, override val html: String): TestReportData(title), HtmlReportData
class FileHtmlReportData(title: String, private val htmlPath: TestCaseFile): TestReportData(title), HtmlReportData {
    override val html: String
        get() {
            return htmlPath.toFile()
                    .readText(StandardCharsets.UTF_8)
        }
}

interface TableReportData {
    val title: String
    val table: Table
}
class SimpleTableReportData(title: String, override val table: Table): TestReportData(title), TableReportData
class FileTableReportData(
        title: String,
        private val tablePath: TestCaseFile,
        private val tableJsonReader: (File) -> Table.TableJson
): TestReportData(title), TableReportData {
    override val table: Table
        get() = tableFromFile(tablePath, tableJsonReader)
}

class ImageReportData(title: String, private val image: TestCaseFile): TestReportData(title) {
    val imagePath: String
        get() = image.relativePath
}
class VideoReportData(title: String, private val video: TestCaseFile): TestReportData(title) {
    val videoPath: String
        get() = video.relativePath
}
class LinkedFileReportData(title: String, val file: TestCaseFile): TestReportData(title) {
    val linkedFilePath: String
        get() = file.relativePath
}

class Table(headerStrings: Collection<String>, rowStrings: Collection<Collection<String>>) {
    val headers: List<Header>
    val rows: List<Row>

    init {
        headers = fixHeaders(headerStrings)
        rows = fixRows(rowStrings, headers)
    }

    class TableJson(
            var headers: Collection<String>? = null,
            var rows: Collection<Collection<String>>? = null
    )

    fun writeToFile(output: TestCaseFile, jsonFileWriter: (File, TableJson) -> Unit) {
        val headerStrings = headers.map { it.title }
        val rowStringLists = rows
                .map {
                    it.cells.map { it.text }
                }
        val adaptedForJson = TableJson(headerStrings, rowStringLists)

        val outputFile = output.create()
        jsonFileWriter(outputFile, adaptedForJson)
    }

    companion object {
        fun tableFromFile(tablePath: TestCaseFile, tableJsonReader: (File) -> TableJson): Table {
            return tableJsonReader(tablePath.toFile())
                    .let {
                        val headers = it.headers
                        val rowsStringLists = it.rows
                        if (rowsStringLists.isNullOrEmpty()) {
                            Table(
                                    it.headers ?: emptyList(),
                                    emptyList()
                            )
                        } else {
                            if (headers.isNullOrEmpty()) {
                                throw RuntimeException("Table headers must not be empty when rows are present")
                            }
                            Table(headers, rowsStringLists)
                        }
                    }
        }

        private fun fixHeaders(headers: Collection<String>) = headers.map { Header(it) }.toList()

        private fun fixRows(rows: Collection<Collection<String>>, fixedHeaders: List<Header>): List<Row> {
            return rows
                    .map { cells ->
                        val fixedCells = cells.mapIndexed { index, cell -> Cell(fixedHeaders[index], cell) }
                        Row(fixedCells)
                    }
                    .toList()
        }
    }
}
fun tableOf(headers: List<String>, vararg rows: List<String>) = Table(headers, rows.toList())

class Row(val cells: List<Cell>)
class Header(val title: String)
class Cell(val header: Header, val text: String) {
    override fun toString(): String = text
}