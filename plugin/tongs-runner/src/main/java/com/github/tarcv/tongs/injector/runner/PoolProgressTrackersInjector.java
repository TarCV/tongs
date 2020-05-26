/*
 * Copyright 2018 TarCV
 * Copyright 2018 Shazam Entertainment Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package com.github.tarcv.tongs.injector.runner;

import com.github.tarcv.tongs.api.devices.Pool;
import com.github.tarcv.tongs.runner.PoolProgressTracker;

import java.util.Map;

import static com.beust.jcommander.internal.Maps.newHashMap;

public class PoolProgressTrackersInjector {

    private PoolProgressTrackersInjector() {}

    public static Map<Pool, PoolProgressTracker> poolProgressTrackers() {
        return newHashMap();
    }
}
