/*
 * Copyright 2018 TarCV
 * Copyright 2017 Shazam Entertainment Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */

package com.github.tarcv.tongs.system.axmlparser;

import com.github.tarcv.tongs.model.Permission;
import org.hamcrest.Matcher;
import org.junit.Test;

import javax.annotation.Nonnull;
import java.io.*;
import java.net.URL;

import static com.github.tarcv.tongs.model.Permission.Builder.permission;
import static com.shazam.shazamcrest.matcher.Matchers.sameBeanAs;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.*;

public class ApplicationInfoFactoryTest {

    @Test
    public void shouldParseRegularPermissions() throws Exception {

        URL testDexResourceUrl = this.getClass().getResource("/app-debug.apk");
        String testDexFile = testDexResourceUrl.getFile();
        File file = new File(testDexFile);
        ApplicationInfo applicationInfo = ApplicationInfoFactory.parseFromFile(file);

        assertThat(applicationInfo.getPermissions(), containsInAnyOrder(
                samePermissionAs("android.permission.RECORD_AUDIO"),
                samePermissionAs("android.permission.GET_ACCOUNTS", 1, 22),
                samePermissionAs("com.github.tarcv.tongstest.A_TEST_PERMISSION", 23, 24)
        ));
    }

    @Nonnull
    @SuppressWarnings("SameParameterValue")
    private Matcher<Permission> samePermissionAs( String permissionName) {
        return sameBeanAs(permission()
                .withPermissionName(permissionName)
                .build());
    }

    @Nonnull
    private Matcher<Permission> samePermissionAs(String permissionName, int minSdk, int maxSdk) {
        return sameBeanAs(permission()
                .withPermissionName(permissionName)
                .withMinSdkVersion(minSdk)
                .withMaxSdkVersion(maxSdk)
                .build());
    }
}