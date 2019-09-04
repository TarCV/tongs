/*
 * Copyright 2019 TarCV
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
package com.github.tarcv.tongs.runner.listeners;

import com.android.ddmlib.testrunner.XmlTestRunListener;
import com.github.tarcv.tongs.system.io.TestCaseFileManager;
import com.github.tarcv.tongs.system.io.FileType;

import java.io.File;

import static com.github.tarcv.tongs.model.TestCaseEvent.newTestCase;

public class AndroidXmlTestRunListener extends XmlTestRunListener {

    private final TestCaseFileManager fileManager;

    public AndroidXmlTestRunListener(TestCaseFileManager fileManager) {
        this.fileManager = fileManager;
    }

    @Override
    protected File getResultFile(File reportDir) {
        return fileManager.createFile(FileType.TEST);
    }
}
