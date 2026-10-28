package eu.stedb.mysyncthing

import android.app.Application
import android.os.StrictMode
import eu.stedb.mysyncthing.stbridge.Stbridge

/**
 * Debug builds only. StrictMode logs ("StrictMode policy violation") what
 * emulator and phone test runs should catch: disk or network use on the main
 * thread, leaked resources, unsafe intents and content URIs. Not cleartext
 * traffic: StrictMode cannot tell which host, and the app's loopback REST and
 * Syncthing's discovery, UPnP and STUN are plain text by design;
 * network_security_config.xml limits the app's own cleartext to loopback.
 * The core logs everything to logcat; release builds send only its warnings
 * and errors (go/bridge logfilter.go).
 */
class DebugApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before the thread policy: this loads the Go library, a debug-only disk read on the main thread.
        Stbridge.verboseLog(true)
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectLeakedSqlLiteObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .detectContentUriWithoutPermission()
                .detectUnsafeIntentLaunch()
                .detectIncorrectContextUse()
                .penaltyLog()
                .build(),
        )
    }
}
