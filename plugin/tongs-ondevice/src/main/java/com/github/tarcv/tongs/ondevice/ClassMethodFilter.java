/*
 * Copyright 2018 TarCV
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

package com.github.tarcv.tongs.ondevice;

import android.os.Bundle;

import org.junit.runner.Description;
import org.junit.runner.manipulation.Filter;

public class ClassMethodFilter extends Filter {
    private final String expectedClassName;
    private final String expectedMethodName;

    public ClassMethodFilter(Bundle bundle) {
        this.expectedClassName = bundle.getString("tongs_filterClass");
        this.expectedMethodName = bundle.getString("tongs_filterMethod");
    }

    @Override
    public boolean shouldRun(Description description) {
        if (description.isTest()) {
            return checkTest(description);
        } else {
            // Allow suite to be run when it contains at least one allowed test
            for (Description child : description.getChildren()) {
                if (shouldRun(child)) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean checkTest(Description description) {
        String className = description.getClassName();
        String methodName = description.getMethodName();
        return expectedClassName.equals(className)
                    && expectedMethodName.equals(methodName);
    }

    @Override
    public String describe() {
        return null;
    }
}
