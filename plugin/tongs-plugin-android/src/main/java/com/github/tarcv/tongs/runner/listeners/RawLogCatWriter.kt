/*
 * Copyright 2020 TarCV
 * Copyright 2014 Shazam Entertainment Limited
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
package com.github.tarcv.tongs.runner.listeners

import com.android.ddmlib.logcat.LogCatMessage
import com.github.tarcv.tongs.api.devices.Device
import com.github.tarcv.tongs.api.devices.Pool
import com.github.tarcv.tongs.api.result.TestCaseFile
import com.github.tarcv.tongs.api.result.TestCaseFileManager
import org.apache.commons.io.IOUtils

internal class RawLogCatWriter(
        private val fileManager: TestCaseFileManager,
        private val pool: Pool,
        private val device: Device,
        private val file: TestCaseFile
) : LogCatWriter {
    override fun writeLogs(logCatMessages: List<LogCatMessage>) {
        file.create()
                .bufferedWriter(Charsets.UTF_8)
                .use { fileWriter ->
                    for (logCatMessage in logCatMessages) {
                        IOUtils.write(logCatMessage.toString(), fileWriter)
                        IOUtils.write("\n", fileWriter)
                    }
                }
    }

}