/*
 * Copyright 2020 TarCV
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package com.github.tarcv.tongs.runner

import com.android.ddmlib.IDevice
import com.android.ddmlib.NullOutputReceiver
import com.android.ddmlib.testrunner.ITestRunListener
import com.android.ddmlib.testrunner.RemoteAndroidTestRunner
import com.android.ddmlib.testrunner.TestIdentifier
import com.github.tarcv.tongs.runner.listeners.BaseListener
import com.github.tarcv.tongs.system.DdmsUtils
import com.github.tarcv.tongs.system.DdmsUtils.unescapeInstrumentationArg
import java.nio.charset.StandardCharsets
import java.util.*

interface IRemoteAndroidTestRunnerFactory {
    fun createRemoteAndroidTestRunner(testPackage: String, testRunner: String, device: IDevice): RemoteAndroidTestRunner
    fun properlyAddInstrumentationArg(runner: RemoteAndroidTestRunner, name: String, value: String)
    fun encodeTestName(name: String): String
}

class RemoteAndroidTestRunnerFactory : IRemoteAndroidTestRunnerFactory {
    override fun createRemoteAndroidTestRunner(testPackage: String, testRunner: String, device: IDevice): RemoteAndroidTestRunner {
        return RemoteAndroidTestRunner(
                testPackage,
                testRunner,
                device)
    }

    override fun properlyAddInstrumentationArg(runner: RemoteAndroidTestRunner, name: String, value: String) {
        DdmsUtils.properlyAddInstrumentationArg(runner, name, value)
    }

    override fun encodeTestName(name: String): String {
        return Base64.getEncoder()
                .encodeToString(name.toByteArray(StandardCharsets.UTF_8))
                .replace("=".toRegex(), "_")
                .replace("\\r|\\n".toRegex(), "")
    }
}

class TestAndroidTestRunnerFactory : IRemoteAndroidTestRunnerFactory {
    override fun createRemoteAndroidTestRunner(testPackage: String, testRunner: String, device: IDevice): RemoteAndroidTestRunner {
        val shouldIncludeApi22Only = device.getProperty("ro.build.version.sdk") == "22"
        return object : RemoteAndroidTestRunner(
                testPackage,
                testRunner,
                device) {
            override fun run(listenersCollection: Collection<ITestRunListener>) {
                // Do not call super.run to avoid compile errors cause by SDK tools version incompatibilities
                device.executeShellCommand(amInstrumentCommand, NullOutputReceiver())
                stubbedRun(listenersCollection)
            }

            private fun stubbedRun(listenersCollection: Collection<ITestRunListener>) {
                val listeners = BroadcastingListener(listenersCollection)

                operator fun Regex.contains(other: String): Boolean = this.matches(other)
                when (val command = withSortedInstrumentedArguments(amInstrumentCommand)) {
                    in logOnlyCommandPattern -> {
                        val filters = logOnlyCommandPattern.matchEntire(command)!!.groupValues[1]
                        val withF2Filter = filters.split(",").contains("com.github.tarcv.test.F2Filter")

                        val testCount: Int
                        val filteredTestIdentifiers: List<String> = TestIdentifiers.asSequence()
                                .filter { shouldIncludeApi22Only || !it.contains("#api22Only") }
                                .filter { !withF2Filter || !it.contains("#filteredByF2Filter")}
                                .toList()
                        testCount = filteredTestIdentifiers.size
                        listeners.testRunStarted("emulators", testCount)
                        filteredTestIdentifiers.forEach {
                            listeners.fireTest(it)
                        }
                        listeners.testRunEnded(100, emptyMap())
                    }
                    in TestIdentifierCommandPattern -> {
                        val (filter, testClass, testMethod) =
                                TestIdentifierCommandPattern.matchEntire(command)
                                        ?.groupValues
                                        ?.drop(1) // group 0 is the entire match
                                        ?.map { unescapeInstrumentationArg(it) }
                                        ?: throw IllegalStateException()
                        listeners.testRunStarted("emulators", 1)
                        listeners.fireTest("$testClass#$testMethod", functionalTestTestIdentifierDuration)
                        listeners.testRunEnded(functionalTestTestIdentifierDuration, emptyMap())
                    }
                    else -> throw IllegalStateException(
                            "Unexpected command (sorted): $command. R1='$logOnlyCommandPattern', R2='$TestIdentifierCommandPattern'")
                }
            }

            private fun withSortedInstrumentedArguments(command: String): String {
                var matched = false
                return command
                        .replace(Regex("(?:\\s+-e\\s\\S+\\s.+?(?=\\s+-e|\\s+com))+")) { match ->
                            if (matched) throw IllegalStateException("Unexpected command: $command")
                            matched = true
                            match.groupValues[0]
                                    .split(Regex("-e\\s+")).asSequence()
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() }
                                    .sorted()
                                    .joinToString("") { " -e $it" }
                        }
            }
        }
    }

    override fun properlyAddInstrumentationArg(runner: RemoteAndroidTestRunner, name: String, value: String) {
        // proper escaping really complicates RemoteAndroidTestRunner#stubbedRun implementation
        //  so skip it here
        runner.addInstrumentationArg(name, value)
    }

    override fun encodeTestName(name: String): String = name

    private fun ITestRunListener.fireTest(TestIdentifier: String, delayMillis: Long = 0) {
        val (className, testName) = TestIdentifier.split("#", limit = 2)
        testStarted(TestIdentifier(className, testName))
        if (delayMillis > 0) {
            Thread.sleep(delayMillis)
        }
        testEnded(TestIdentifier(className, testName), emptyMap())
    }

    companion object {
        const val functionalTestTestIdentifierDuration = 2345L

        private const val expectedTestPackage = "com.github.tarcv.tongstestapp.f[12].test"
        private const val expectedTestRunner = "(?:android.support.test.runner.AndroidJUnitRunner|com.github.tarcv.test.f2.TestRunner)"
        val logOnlyCommandPattern =
                ("am\\s+instrument\\s+-w\\s+-r" +
                        " -e filter ((?:\\S+,)?com.github.tarcv.tongs.ondevice.AnnontationReadingFilter(,?:\\S+)?)" +
                        " -e log true" +
                        " -e test_argument \\S+" +
                        """\s+$expectedTestPackage\/$expectedTestRunner""")
                        .replace(".", "\\.")
                        .replace(" -", "\\s+-")
                        .toRegex()
        val TestIdentifierCommandPattern =
                ("am\\s+instrument\\s+-w\\s+-r" +
                        " -e filter ()" +
                        " -e test_argument \\S+" +
                        " -e tongs_filterClass ()" +
                        " -e tongs_filterMethod ()" +
                        """\s+$expectedTestPackage\/$expectedTestRunner""")
                        .replace(".", "\\.")
                        .replace(" -", "\\s+-")
                        .replace("()", "(.+?)")
                        .toRegex()
        private val TestIdentifiers = listOf("""com.github.tarcv.test.DangerousNamesTest#test[param = """ + '$' + """THIS_IS_NOT_A_VAR]""",
                """com.github.tarcv.test.DangerousNamesTest#test[param =        1       ]""",
                """com.github.tarcv.test.DangerousNamesTest#test[param = #######]""",
                """com.github.tarcv.test.DangerousNamesTest#test[param = !!!!!!!]""",
                """com.github.tarcv.test.DangerousNamesTest#test[param = ''''''']""",
                "com.github.tarcv.test.DangerousNamesTest#test[param = \"\"\"\"\"\"\"\"]",
                """com.github.tarcv.test.DangerousNamesTest#test[param = ()$(echo)`echo`()$(echo)`echo`()$(echo)`echo`()$(echo)`echo`()$(echo)`echo`()$(echo)`echo`()$(echo)`echo`]""",
                """com.github.tarcv.test.DangerousNamesTest#test[param = * *.* * *.* * *.* * *.* * *.* * *.* * *.* * *.* *]""",
                """com.github.tarcv.test.DangerousNamesTest#test[param = . .. . .. . .. . .. . .. . .. . .. . .. . .. . ..]""",
                """com.github.tarcv.test.DangerousNamesTest#test[param = |&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%]""",
                """com.github.tarcv.test.DangerousNamesTest#test[param = Non-ASCII: ° © ± ¶ ½ » ѱ ∆]""",
                """com.github.tarcv.test.DangerousNamesTest#test[param = ; function {}; while {}; for {}; do {}; done {}; exit]""",
                """com.github.tarcv.test.FilteredTest#api22Only[1]""",
                """com.github.tarcv.test.FilteredTest#api22Only[2]""",
                """com.github.tarcv.test.FilteredTest#api22Only[3]""",
                """com.github.tarcv.test.FilteredTest#api22Only[4]""",
                """com.github.tarcv.test.FilteredTest#filteredByF2Filter[1]""",
                """com.github.tarcv.test.FilteredTest#filteredByF2Filter[2]""",
                """com.github.tarcv.test.FilteredTest#filteredByF2Filter[3]""",
                """com.github.tarcv.test.FilteredTest#filteredByF2Filter[4]""",
                """com.github.tarcv.test.GrantPermissionsForClassTest#testPermissionGranted1""",
                """com.github.tarcv.test.GrantPermissionsForClassTest#testPermissionGranted2""",
                """com.github.tarcv.test.GrantPermissionsForInheritedClassTest#testPermissionGranted1""",
                """com.github.tarcv.test.GrantPermissionsForInheritedClassTest#testPermissionGranted2""",
                """com.github.tarcv.test.GrantPermissionsTest#testPermissionGranted""",
                """com.github.tarcv.test.GrantPermissionsTest#testNoPermissionByDefault""",
                """com.github.tarcv.test.NoPermissionsForOverridesTest#testNoPermissionForAbstractOverrides""",
                """com.github.tarcv.test.NoPermissionsForOverridesTest#testNoPermissionForNormalOverrides""",
                """com.github.tarcv.test.NormalTest#test""",
                """com.github.tarcv.test.ParameterizedNamedTest#test[param = 1]""",
                """com.github.tarcv.test.ParameterizedNamedTest#test[param = 2]""",
                """com.github.tarcv.test.ParameterizedNamedTest#test[param = 3]""",
                """com.github.tarcv.test.ParameterizedNamedTest#test[param = 4]""",
                """com.github.tarcv.test.ParameterizedNamedTest#test[param = 5]""",
                """com.github.tarcv.test.ParameterizedNamedTest#test[param = 6]""",
                """com.github.tarcv.test.ParameterizedNamedTest#test[param = 7]""",
                """com.github.tarcv.test.ParameterizedNamedTest#test[param = 8]""",
                """com.github.tarcv.test.ParameterizedTest#test[1]""",
                """com.github.tarcv.test.ParameterizedTest#test[2]""",
                """com.github.tarcv.test.ParameterizedTest#test[3]""",
                """com.github.tarcv.test.ParameterizedTest#test[4]""",
                """com.github.tarcv.test.ParameterizedTest#test[5]""",
                """com.github.tarcv.test.ParameterizedTest#test[6]""",
                """com.github.tarcv.test.ParameterizedTest#test[7]""",
                """com.github.tarcv.test.ParameterizedTest#test[8]""",
                """com.github.tarcv.test.PropertiesTest#normalPropertiesTest""",
                """com.github.tarcv.test.PropertiesTest#normalPropertyPairsTest""",
                """com.github.tarcv.test.ResetPrefsTest#testPrefsAreClearedBetweenTests[0]""",
                """com.github.tarcv.test.ResetPrefsTest#testPrefsAreClearedBetweenTests[1]""",
                """com.github.tarcv.test.ResetPrefsTest#testPrefsAreClearedBetweenTests[2]""",
                """com.github.tarcv.test.ResetPrefsTest#testPrefsAreClearedBetweenTests[3]"""
        )

        val logcatLines = listOf("[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param = ${'$'}THIS_IS_NOT_A_VAR]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param =        1       ]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param = #######]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param = !!!!!!!]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param = ''''''']","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param = \"\"\"\"\"\"\"\"]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param = ()${'$'}(echo)`echo`()${'$'}(echo)`echo`()${'$'}(echo)`echo`()${'$'}(echo)`echo`()${'$'}(echo)`echo`()${'$'}(echo)`echo`()${'$'}(echo)`echo`]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param = * *.* * *.* * *.* * *.* * *.* * *.* * *.* * *.* *]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param = . .. . .. . .. . .. . .. . .. . .. . .. . .. . ..]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param = |&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%|&;<>()${'$'}`?[]#~=%]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.DangerousNamesTest","testMethod":"test[param = ; function {}; while {}; for {}; do {}; done {}; exit]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.GrantPermissionsForClassTest","testMethod":"testPermissionGranted1","annotations":[{"annotationType":"com.github.tarcv.tongs.GrantPermission","value":["android.permission.WRITE_CALENDAR"]},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.GrantPermissionsForClassTest","testMethod":"testPermissionGranted2","annotations":[{"annotationType":"com.github.tarcv.tongs.GrantPermission","value":["android.permission.WRITE_CALENDAR"]},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.GrantPermissionsForInheritedClassTest","testMethod":"testPermissionGranted1","annotations":[{"annotationType":"com.github.tarcv.tongs.GrantPermission","value":["android.permission.WRITE_CALENDAR"]},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.GrantPermissionsForInheritedClassTest","testMethod":"testPermissionGranted2","annotations":[{"annotationType":"com.github.tarcv.tongs.GrantPermission","value":["android.permission.WRITE_CALENDAR"]},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.GrantPermissionsTest","testMethod":"testPermissionGranted","annotations":[{"annotationType":"com.github.tarcv.tongs.GrantPermission","value":["android.permission.WRITE_CALENDAR"]},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.GrantPermissionsTest","testMethod":"testNoPermissionByDefault","annotations":[{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.NoPermissionsForOverridesTest","testMethod":"testNoPermissionForOverrides","annotations":[{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.NormalTest","testMethod":"test","annotations":[{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedNamedTest","testMethod":"test[param = 1]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedNamedTest","testMethod":"test[param = 2]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedNamedTest","testMethod":"test[param = 3]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedNamedTest","testMethod":"test[param = 4]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedNamedTest","testMethod":"test[param = 5]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedNamedTest","testMethod":"test[param = 6]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedNamedTest","testMethod":"test[param = 7]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedNamedTest","testMethod":"test[param = 8]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedTest","testMethod":"test[0]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedTest","testMethod":"test[1]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedTest","testMethod":"test[2]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedTest","testMethod":"test[3]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedTest","testMethod":"test[4]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedTest","testMethod":"test[5]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedTest","testMethod":"test[6]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ParameterizedTest","testMethod":"test[7]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.PropertiesTest","testMethod":"normalPropertiesTest","annotations":[{"annotationType":"com.github.tarcv.tongs.TestProperties","keys":["x","y"],"values":["1","2"]},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.PropertiesTest","testMethod":"normalPropertyPairsTest","annotations":[{"annotationType":"com.github.tarcv.tongs.TestPropertyPairs","value":["v","1","w","2"]},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ResetPrefsTest","testMethod":"testPrefsAreClearedBetweenTests[0]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ResetPrefsTest","testMethod":"testPrefsAreClearedBetweenTests[1]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ResetPrefsTest","testMethod":"testPrefsAreClearedBetweenTests[2]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                "",
                "[ LOGCAT_TIME 1234: 5678 I/Tongs.TestInfo ]",
                """0b2d3157-LOGCAT_INDEX:{"testClass":"com.github.tarcv.test.ResetPrefsTest","testMethod":"testPrefsAreClearedBetweenTests[3]","annotations":[{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.runner.RunWith","value":"class org.junit.runners.Parameterized"},{"annotationType":"org.junit.Test","expected":"class org.junit.Test${'$'}None","timeout":0}]},""",
                ""
        )
    }
}

private class BroadcastingListener(
        private val targetListeners: Collection<ITestRunListener>
) : BaseListener(null) {
    override fun testRunStarted(runName: String?, testCount: Int) {
        targetListeners.forEach {
            it.testRunStarted(runName, testCount)
        }
    }

    override fun testStarted(test: TestIdentifier?) {
        targetListeners.forEach {
            it.testStarted(test)
        }
    }

    override fun testAssumptionFailure(test: TestIdentifier?, trace: String?) {
        targetListeners.forEach {
            it.testAssumptionFailure(test, trace)
        }
    }

    override fun testRunStopped(elapsedTime: Long) {
        targetListeners.forEach {
            it.testRunStopped(elapsedTime)
        }
    }

    override fun testFailed(test: TestIdentifier?, trace: String?) {
        targetListeners.forEach {
            it.testFailed(test, trace)
        }
    }

    override fun testEnded(test: TestIdentifier?, testMetrics: Map<String, String>?) {
        targetListeners.forEach {
            it.testEnded(test, testMetrics)
        }
    }

    override fun testIgnored(test: TestIdentifier?) {
        targetListeners.forEach {
            it.testIgnored(test)
        }
    }

    override fun testRunFailed(errorMessage: String?) {
        targetListeners.forEach {
            it.testRunFailed(errorMessage)
        }
    }

    override fun testRunEnded(elapsedTime: Long, runMetrics: Map<String, String>?) {
        try {
            targetListeners.forEach {
                it.testRunEnded(elapsedTime, runMetrics)
            }
        } finally {
            onWorkFinished()
        }
    }

}