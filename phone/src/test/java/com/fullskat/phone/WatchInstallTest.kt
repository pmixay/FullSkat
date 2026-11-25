package com.fullskat.phone

import com.fullskat.phone.watchinstall.AdbProblem
import com.fullskat.phone.watchinstall.AppVersion
import com.fullskat.phone.watchinstall.InstallAction
import com.fullskat.phone.watchinstall.InstallError
import com.fullskat.phone.watchinstall.InstallOutcome
import com.fullskat.phone.watchinstall.MAX_PAIRED_WATCHES
import com.fullskat.phone.watchinstall.PairedWatch
import com.fullskat.phone.watchinstall.WatchInfo
import com.fullskat.phone.watchinstall.WatchService
import com.fullskat.phone.watchinstall.attemptKey
import com.fullskat.phone.watchinstall.classifyConnectError
import com.fullskat.phone.watchinstall.classifyPairError
import com.fullskat.phone.watchinstall.decodePairedWatches
import com.fullskat.phone.watchinstall.encodePairedWatches
import com.fullskat.phone.watchinstall.filterPairingCode
import com.fullskat.phone.watchinstall.forget
import com.fullskat.phone.watchinstall.hostPort
import com.fullskat.phone.watchinstall.installAction
import com.fullskat.phone.watchinstall.installFinished
import com.fullskat.phone.watchinstall.launchSucceeded
import com.fullskat.phone.watchinstall.nextConnectTarget
import com.fullskat.phone.watchinstall.parseGetprop
import com.fullskat.phone.watchinstall.parseHostPort
import com.fullskat.phone.watchinstall.parseInstallOutput
import com.fullskat.phone.watchinstall.parseInstalledVersion
import com.fullskat.phone.watchinstall.parseResolvedActivity
import com.fullskat.phone.watchinstall.remember
import com.fullskat.phone.watchinstall.sameWatch
import com.fullskat.phone.watchinstall.serialOf
import com.fullskat.phone.watchinstall.uninstallSucceeded
import com.fullskat.phone.watchinstall.watchDisplayName
import com.fullskat.phone.watchinstall.watchInfo
import com.fullskat.phone.watchinstall.watchModelName
import com.fullskat.phone.watchinstall.wearOsVersion
import io.github.muntashirakon.adb.AdbPairingRequiredException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.NoSuchAlgorithmException

class WatchInstallTest {
    // ------------------------------------------------------------------ cmd package install

    @Test fun installSuccess() {
        assertEquals(InstallOutcome.Success, parseInstallOutput("Success\n"))
        assertEquals(InstallOutcome.Success, parseInstallOutput("Performing Streamed Install\r\nSuccess\r\n"))
    }

    @Test fun installFailures() {
        val incompatible = parseInstallOutput(
            "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package com.fullskat.watch signatures do not match newer version; ignoring!]\n",
        ) as InstallOutcome.Failure
        assertEquals(InstallError.UPDATE_INCOMPATIBLE, incompatible.error)
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", incompatible.code)
        assertTrue(incompatible.detail.startsWith("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package"))

        fun error(output: String) = (parseInstallOutput(output) as InstallOutcome.Failure).error
        assertEquals(InstallError.INSUFFICIENT_STORAGE, error("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]\n"))
        assertEquals(InstallError.VERSION_DOWNGRADE, error("Failure [INSTALL_FAILED_VERSION_DOWNGRADE: Downgrade detected: Update version code 3 is older than current 4]\n"))
        assertEquals(InstallError.NO_MATCHING_ABIS, error("Failure [INSTALL_FAILED_NO_MATCHING_ABIS: Failed to extract native libraries, res=-113]\n"))
        assertEquals(InstallError.OLDER_SDK, error("Failure [INSTALL_FAILED_OLDER_SDK: Requires newer sdk version #33 (current version is #30)]\n"))
        assertEquals(InstallError.INVALID_APK, error("Failure [INSTALL_PARSE_FAILED_NOT_APK: Failed to parse /data/app/vmdl1.tmp/base.apk]\n"))
        assertEquals(InstallError.OTHER, error("Failure [INSTALL_FAILED_ABORTED: User rejected permissions]\n"))
        assertEquals(InstallError.OTHER, error("Error: java.lang.IllegalArgumentException: Size must be positive\n"))
        // Nothing usable: the stream ended early.
        assertEquals(InstallError.LOST, error(""))
    }

    @Test fun installFinishedOnlyOnACompleteFinalLine() {
        assertFalse(installFinished(""))
        assertFalse(installFinished("Succ"))
        assertFalse(installFinished("Success"))
        assertTrue(installFinished("Success\n"))
        assertFalse(installFinished("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing"))
        assertTrue(installFinished("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package]\n"))
    }

    @Test fun uninstall() {
        assertTrue(uninstallSucceeded("Success\n"))
        assertTrue(uninstallSucceeded("Failure [not installed for 0]\n"))
        assertFalse(uninstallSucceeded("Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]\n"))
    }

    // ------------------------------------------------------------------ dumpsys, getprop

    private val dumpsys = """
        Activity Resolver Table:
          Non-Data Actions:
              android.intent.action.MAIN:
                8f3c2a1 com.fullskat.watch/.MainActivity filter 4c1d2e0

        Packages:
          Package [com.fullskat.watch] (8f3c2a1):
            appId=10234
            pkg=Package{5b7e9c3 com.fullskat.watch}
            codePath=/data/app/~~abc==/com.fullskat.watch-xyz==
            versionCode=42 minSdk=33 targetSdk=37
            minExtensionVersions=[]
            versionName=1.0.42
            splits=[base]
            apkSigningVersion=2
            flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ]
            User 0: ceDataInode=12345 installed=true hidden=false

        Queries:
          system apps queryable: false
    """.trimIndent()

    @Test fun installedVersionFromDumpsys() {
        assertEquals(AppVersion("1.0.42", 42), parseInstalledVersion(dumpsys))
        assertNull(parseInstalledVersion("Unable to find package: com.fullskat.watch\n"))
        assertNull(parseInstalledVersion(""))
        // Another package's section does not count.
        assertNull(parseInstalledVersion("Packages:\n  Package [com.fullskat.watch.debug] (1):\n    versionCode=7 minSdk=33\n    versionName=1.0.0-debug\n"))
        assertEquals(AppVersion("1.0.0-debug", 7), parseInstalledVersion("Packages:\n  Package [com.fullskat.watch.debug] (1):\n    versionCode=7 minSdk=33\n    versionName=1.0.0-debug\n", "com.fullskat.watch.debug"))
    }

    @Test fun getpropAndWatchInfo() {
        val props = parseGetprop(
            """
            [ro.build.version.release]: [16]
            [ro.build.version.sdk]: [36]
            [ro.product.cpu.abilist]: [arm64-v8a,armeabi-v7a,armeabi]
            [ro.product.manufacturer]: [samsung]
            [ro.product.model]: [SM-L305F]
            [ro.serialno]: [R5CT1234567]
            [persist.sys.empty]: []
            garbage line
            """.trimIndent(),
        )
        assertEquals("SM-L305F", props["ro.product.model"])
        assertEquals("", props["persist.sys.empty"])
        val info = watchInfo(props, dumpsys)
        assertEquals("SM-L305F", info.model)
        assertEquals("samsung", info.manufacturer)
        assertEquals("16", info.androidVersion)
        assertEquals(36, info.sdk)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), info.abis)
        assertEquals(AppVersion("1.0.42", 42), info.installed)
        assertNull(watchInfo(props, "").installed)
        assertEquals("R5CT1234567", info.serial)
        assertEquals("", info.deviceName)
        assertEquals("Samsung SM-L305F", watchDisplayName(info))
        // `settings get global device_name`: the watch's own name, or "null" when it has none.
        val named = watchInfo(props, dumpsys, "Galaxy Watch7 (D4F2)\n")
        assertEquals("Galaxy Watch7 (D4F2)", watchDisplayName(named))
        assertEquals("Samsung SM-L305F", watchModelName(named))
        assertEquals("", watchInfo(props, dumpsys, "null\n").deviceName)
        assertEquals("Google Pixel Watch 3", watchModelName(WatchInfo("Pixel Watch 3", "Google", "16", 36, emptyList(), null)))
        assertEquals("Xiaomi Watch 2", watchModelName(WatchInfo("Xiaomi Watch 2", "Xiaomi", "13", 33, emptyList(), null)))
    }

    // ------------------------------------------------------------------ opening the app after the install

    @Test fun launcherActivity() {
        assertEquals(
            "com.fullskat.watch/.MainActivity",
            parseResolvedActivity("priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=false\ncom.fullskat.watch/.MainActivity\n"),
        )
        assertNull(parseResolvedActivity("No activity found\n"))
        assertNull(parseResolvedActivity(""))
    }

    @Test fun launchResults() {
        assertTrue(
            launchSucceeded(
                "Starting: Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] cmp=com.fullskat.watch/.MainActivity }\n" +
                    "Status: ok\nLaunchState: COLD\nActivity: com.fullskat.watch/.MainActivity\nTotalTime: 912\nWaitTime: 920\nComplete\n",
            ),
        )
        assertTrue(
            launchSucceeded(
                "Starting: Intent { cmp=com.fullskat.watch/.MainActivity }\n" +
                    "Warning: Activity not started, its current task has been brought to the front\nStatus: ok\n",
            ),
        )
        assertFalse(
            launchSucceeded(
                "Starting: Intent { cmp=com.fullskat.watch/.MainActivity }\nError type 3\n" +
                    "Error: Activity class {com.fullskat.watch/com.fullskat.watch.MainActivity} does not exist.\n",
            ),
        )
        assertFalse(launchSucceeded(""))
    }

    // ------------------------------------------------------------------ remembered watches

    private fun paired(key: String, serviceName: String? = null, serial: String? = null, host: String = "192.168.1.23", port: Int = 0, used: Long = 0) =
        PairedWatch(key, serviceName, serial, name = serial ?: host, host = host, port = port, pairedAt = 1, lastUsedAt = used)

    @Test fun pairedWatchMatchesItsServices() {
        val byName = paired("a", serviceName = "adb-R5CT1234567-AbCdEf")
        assertTrue(byName.matches(WatchService("adb-R5CT1234567-AbCdEf", "192.168.1.40", 41235)))
        assertTrue(byName.matches(WatchService("adb-R5CT1234567-AbCdEf (2)", "192.168.1.40", 41235))) // renamed on a clash
        assertFalse(byName.matches(WatchService("adb-R9XX0000000-QwErTy", "192.168.1.23", 41235)))
        // Connected by address first: the serial (ro.serialno) is what the service name carries.
        val bySerial = paired("b", serial = "R5CT1234567")
        assertTrue(bySerial.matches(WatchService("adb-R5CT1234567-AbCdEf", "10.0.0.7", 5555)))
        assertFalse(bySerial.matches(WatchService("adb-R9XX0000000-QwErTy", "192.168.1.23", 5555)))
        // Paired by address only: nothing but the address to go by.
        val byHost = paired("c")
        assertTrue(byHost.matches(WatchService("adb-R9XX0000000-QwErTy", "192.168.1.23", 41235)))
        assertFalse(byHost.matches(WatchService("adb-R9XX0000000-QwErTy", "192.168.1.24", 41235)))
    }

    @Test fun rememberingReplacesTheSameWatch() {
        val watch = paired("a", serviceName = "adb-R5CT1234567-AbCdEf", used = 1)
        val other = paired("b", serial = "R9XX0000000", used = 2)
        val list = listOf(other, watch)
        // Paired again: a new record for the same watch replaces the old one and goes first.
        val again = paired("c", serviceName = "adb-R5CT1234567-AbCdEf", used = 3)
        assertEquals(listOf("c", "b"), list.remember(again).map { it.key })
        // Updated after connecting (same key, serial learnt): still one record.
        val updated = watch.copy(serial = "R5CT1234567", port = 41235)
        assertEquals(listOf(updated, other), list.remember(updated))
        // The same serial under another service name is still the same watch.
        assertEquals(listOf("d", "a"), list.remember(paired("d", serial = "R9XX0000000")).map { it.key })
        assertEquals(listOf(watch), list.forget(other))
        val many = (1..12).fold(emptyList<PairedWatch>()) { acc, i -> acc.remember(paired("k$i", serial = "S$i")) }
        assertEquals(MAX_PAIRED_WATCHES, many.size)
        assertEquals("k12", many.first().key)
    }

    @Test fun pairedWatchesSurviveSaving() {
        val list = listOf(
            PairedWatch("a", "adb-R5CT1234567-AbCdEf", "R5CT1234567", "Galaxy Watch7 (D4F2)", "192.168.1.23", 41235, 100, 200),
            PairedWatch("b", null, null, "192.168.1.40", "192.168.1.40", 0, 300, 300),
        )
        assertEquals(list, decodePairedWatches(encodePairedWatches(list)))
        assertEquals(emptyList<PairedWatch>(), decodePairedWatches("not json"))
        assertEquals(emptyList<PairedWatch>(), decodePairedWatches(""))
        // A broken record is skipped, the rest is kept.
        assertEquals(listOf("ok"), decodePairedWatches("""[{"name":"x"},{"key":"ok","name":"W","host":"10.0.0.2"}]""").map { it.key })
    }

    @Test fun connectsToTheMostRecentWatchSeenFirst() {
        val recent = paired("a", serviceName = "adb-AAAA-1", used = 9)
        val older = paired("b", serviceName = "adb-BBBB-2", used = 1)
        val services = listOf(
            WatchService("adb-BBBB-2", "192.168.1.30", 40001),
            WatchService("adb-AAAA-1", "192.168.1.20", 40002),
            WatchService("adb-CCCC-3", "192.168.1.50", 40003), // not paired: never picked
        )
        val (service, watch) = nextConnectTarget(services, listOf(recent, older), emptySet())!!
        assertEquals("a", watch.key)
        assertEquals(40002, service.port)
        // Tried already: the next watch.
        assertEquals("b", nextConnectTarget(services, listOf(recent, older), setOf(attemptKey("192.168.1.20", 40002)))!!.second.key)
        // Wireless debugging switched on again: a new port is a new attempt.
        val restarted = services + WatchService("adb-AAAA-1", "192.168.1.20", 40100)
        assertEquals(40100, nextConnectTarget(restarted, listOf(recent), setOf(attemptKey("192.168.1.20", 40002)))!!.first.port)
        assertNull(nextConnectTarget(services, listOf(recent, older), setOf(attemptKey("192.168.1.20", 40002), attemptKey("192.168.1.30", 40001))))
        assertNull(nextConnectTarget(services, emptyList(), emptySet()))
    }

    @Test fun wearOsReleases() {
        assertEquals("4", wearOsVersion(33))
        assertEquals("5", wearOsVersion(34))
        assertEquals("6", wearOsVersion(36))
        assertNull(wearOsVersion(31))
    }

    // ------------------------------------------------------------------ versions

    @Test fun installActions() {
        val bundled = AppVersion("1.0.42", 42)
        assertEquals(InstallAction.INSTALL, installAction(bundled, null))
        assertEquals(InstallAction.UPDATE, installAction(bundled, AppVersion("1.0.41", 41)))
        assertEquals(InstallAction.REINSTALL, installAction(bundled, AppVersion("1.0.42", 42)))
        assertEquals(InstallAction.DOWNGRADE, installAction(bundled, AppVersion("1.0.50", 50)))
    }

    @Test fun versionLabels() {
        assertEquals("1.0.42", AppVersion("1.0.42", 42).label)
        assertEquals("v2.0 (7)", AppVersion("v2.0", 7).label)
        assertEquals("1.0.0 (1)", AppVersion("1.0.0", 1).label)
        assertEquals("1.0.12 (2)", AppVersion("1.0.12", 2).label)
        assertEquals("5", AppVersion("", 5).label)
    }

    // ------------------------------------------------------------------ addresses and mDNS names

    @Test fun hostPortParsing() {
        assertEquals("192.168.1.23" to 41235, parseHostPort("192.168.1.23:41235"))
        assertEquals("192.168.1.23" to 41235, parseHostPort("  192.168.1.23 : 41235 "))
        assertEquals("fe80::1%wlan0" to 5555, parseHostPort("[fe80::1%wlan0]:5555"))
        assertEquals("watch.local" to 5555, parseHostPort("watch.local:5555"))
        assertNull(parseHostPort("192.168.1.23"))
        assertNull(parseHostPort("192.168.1.23:"))
        assertNull(parseHostPort("192.168.1.23:70000"))
        assertNull(parseHostPort("192.168.1.300:5555"))
        assertNull(parseHostPort(":5555"))
        assertNull(parseHostPort("fe80::1:5555")) // IPv6 needs brackets
        assertNull(parseHostPort(""))
        assertEquals("192.168.1.23:5555", hostPort("192.168.1.23", 5555))
        assertEquals("[fe80::1]:5555", hostPort("fe80::1", 5555))
    }

    @Test fun serviceNames() {
        assertEquals("R5CT1234567", serialOf("adb-R5CT1234567-AbCdEf"))
        assertEquals("R5CT1234567", serialOf("adb-R5CT1234567-AbCdEf (2)"))
        assertEquals("1A2B-3C4D", serialOf("adb-1A2B-3C4D-xYz123"))
        assertNull(serialOf("studio-Ab1Cd2"))
        assertEquals("R5CT1234567", WatchService("adb-R5CT1234567-AbCdEf", "192.168.1.23", 37145).serial)
        assertEquals("192.168.1.23:37145", WatchService("adb-R5CT1234567-AbCdEf", "192.168.1.23", 37145).address)
        assertTrue(sameWatch("adb-R5CT1234567-AbCdEf", "adb-R5CT1234567-AbCdEf (2)"))
        assertFalse(sameWatch("adb-R5CT1234567-AbCdEf", "adb-R5CT7654321-AbCdEf"))
    }

    @Test fun pairingCodeKeepsSixDigits() {
        assertEquals("482913", filterPairingCode("482 913"))
        assertEquals("482913", filterPairingCode("4829131"))
        assertEquals("12", filterPairingCode("a1b2"))
    }

    // ------------------------------------------------------------------ errors

    @Test fun pairErrors() {
        assertEquals(AdbProblem.WRONG_CODE, classifyPairError(IOException("Could not exchange peer info.")))
        assertEquals(AdbProblem.WRONG_CODE, classifyPairError(EOFException()))
        assertEquals(AdbProblem.REFUSED, classifyPairError(ConnectException("failed to connect to /192.168.1.23 (port 37145): connect failed: ECONNREFUSED (Connection refused)")))
        assertEquals(AdbProblem.UNREACHABLE, classifyPairError(ConnectException("failed to connect to /10.0.0.5 (port 37145): connect failed: ENETUNREACH (Network is unreachable)")))
        assertEquals(AdbProblem.UNREACHABLE, classifyPairError(NoRouteToHostException("No route to host")))
        assertEquals(AdbProblem.TIMEOUT, classifyPairError(SocketTimeoutException("connect timed out")))
        assertEquals(AdbProblem.BAD_ADDRESS, classifyPairError(UnknownHostException("watch.local")))
        assertEquals(AdbProblem.NO_TLS, classifyPairError(NoSuchAlgorithmException("TLSv1.3 isn't supported")))
        assertEquals(AdbProblem.OTHER, classifyPairError(IllegalStateException("odd")))
    }

    @Test fun connectErrors() {
        assertEquals(AdbProblem.NOT_PAIRED, classifyConnectError(AdbPairingRequiredException("ADB pairing is required.")))
        assertEquals(AdbProblem.NOT_PAIRED, classifyConnectError(IOException("Connection failed")))
        assertEquals(AdbProblem.REFUSED, classifyConnectError(ConnectException("connect failed: ECONNREFUSED (Connection refused)")))
        // libadb wraps socket failures in a bare IOException.
        assertEquals(AdbProblem.UNREACHABLE, classifyConnectError(IOException().initCause(NoRouteToHostException("No route to host"))))
        assertEquals(AdbProblem.TIMEOUT, classifyConnectError(SocketTimeoutException("The watch did not complete the connection")))
        assertEquals(AdbProblem.LOST, classifyConnectError(IOException("Stream closed.")))
    }
}
