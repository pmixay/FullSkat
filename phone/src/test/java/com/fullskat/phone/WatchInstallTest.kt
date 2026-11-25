package com.fullskat.phone

import com.fullskat.phone.watchinstall.AdbProblem
import com.fullskat.phone.watchinstall.AppVersion
import com.fullskat.phone.watchinstall.InstallAction
import com.fullskat.phone.watchinstall.InstallError
import com.fullskat.phone.watchinstall.InstallOutcome
import com.fullskat.phone.watchinstall.WatchService
import com.fullskat.phone.watchinstall.classifyConnectError
import com.fullskat.phone.watchinstall.classifyPairError
import com.fullskat.phone.watchinstall.filterPairingCode
import com.fullskat.phone.watchinstall.hostPort
import com.fullskat.phone.watchinstall.installAction
import com.fullskat.phone.watchinstall.installFinished
import com.fullskat.phone.watchinstall.parseGetprop
import com.fullskat.phone.watchinstall.parseHostPort
import com.fullskat.phone.watchinstall.parseInstallOutput
import com.fullskat.phone.watchinstall.parseInstalledVersion
import com.fullskat.phone.watchinstall.sameWatch
import com.fullskat.phone.watchinstall.serialOf
import com.fullskat.phone.watchinstall.uninstallSucceeded
import com.fullskat.phone.watchinstall.watchInfo
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
