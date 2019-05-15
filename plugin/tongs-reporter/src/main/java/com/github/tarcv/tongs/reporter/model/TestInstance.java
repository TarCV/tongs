/*
 * Copyright 2018 TarCV
 * Copyright 2015 Shazam Entertainment Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package com.github.tarcv.tongs.reporter.model;

import com.github.tarcv.tongs.summary.ResultStatus;
import com.github.tarcv.tongs.summary.TestResult;

import javax.annotation.Nonnull;

import static com.github.tarcv.tongs.reporter.model.Status.*;

public class TestInstance {
    private final Status status;
    private final String link;

    @Nonnull
    public Status getStatus() {
        return status;
    }

    public String getLink() {
        return link;
    }

    private TestInstance(Builder builder) {
        this.status = builder.resultStatus;
        this.link = builder.link;
    }

    public static class Builder {
        private Status resultStatus = Status.MISSING;
        private String link;

        public static Builder testInstance() {
            return new Builder();
        }

        public Builder withResultStatusFrom(TestResult resultStatus) {
            Status status = fromResultStatus(resultStatus.getResultStatus());
            if( status == PASS && resultStatus.getTotalFailureCount() > 0){
                status = WARN;
            }
            this.resultStatus = status;
            return this;
        }

        public Builder withLink(String link) {
            this.link = link;
            return this;
        }

        public TestInstance build() {
            return new TestInstance(this);
        }
    }

    private static Status fromResultStatus(ResultStatus resultStatus) {
        if (resultStatus == null) {
            return MISSING;
        } else if (resultStatus == ResultStatus.ERROR || resultStatus == ResultStatus.FAIL) {
            return FAIL;
        } else {
            return PASS;
        }
    }
}