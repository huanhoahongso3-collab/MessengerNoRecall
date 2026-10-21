package dhp.tpl.norecall

import android.os.Build
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Method

/**
 * DexKit-backed method resolver with per-build APK caching.
 *
 * Because Messenger obfuscates every class/method name on every release, we
 * never hardcode a class or method name.  Instead we describe each target by a
 * set of stable string constants, log messages, or structural fingerprints that
 * DexKit can locate at runtime.  The found descriptors are cached next to the
 * app's data dir so the scan only runs once per APK build.
 */
object Resolver {

    const val TAG = "MessengerNoUnsent"

    @Volatile private var dexkitLoaded = false

    /**
     * Find [count] methods matching [find], returning their [Method] reflections.
     *
     * @param cacheKey   Short stable string used to name the on-disk cache file
     *                   (one file per logical group of methods).
     * @param count      Exact number of methods [find] must return.
     * @param find       Lambda that receives an open [DexKitBridge] and must
     *                   return exactly [count] [MethodData] items, or null if
     *                   the fingerprint doesn't match this APK version.
     */
    fun resolve(
        lp: XC_LoadPackage.LoadPackageParam,
        modulePath: String,
        cacheKey: String,
        count: Int,
        find: (DexKitBridge) -> List<MethodData>?,
    ): List<Method>? {
        val cl  = lp.classLoader
        val apk = File(lp.appInfo.sourceDir)
        // Cache key: file size + last-modified covers any APK update
        val sig  = "${apk.length()}-${apk.lastModified()}"
        val cache = File(lp.appInfo.dataDir, "msgnounsent.$cacheKey.cache")

        // Try reading a valid cache first
        try {
            val lines = cache.takeIf { it.exists() }?.readLines()
            if (lines != null && lines.size == count + 1 && lines[0] == sig) {
                return lines.drop(1).map { DexMethod(it).getMethodInstance(cl) }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: cache unusable [$cacheKey]: $t")
        }

        // Load the native DexKit library from inside the module APK
        if (!dexkitLoaded) {
            System.load("$modulePath!/lib/${Build.SUPPORTED_ABIS[0]}/libdexkit.so")
            dexkitLoaded = true
        }

        DexKitBridge.create(apk.absolutePath).use { bridge ->
            val found = find(bridge) ?: run {
                XposedBridge.log("$TAG: fingerprint [$cacheKey] returned null")
                return null
            }
            if (found.size != count) {
                XposedBridge.log("$TAG: fingerprint [$cacheKey] matched ${found.size} methods, expected $count")
                return null
            }
            val methods = found.map { it.getMethodInstance(cl) }
            // Persist cache
            try {
                cache.writeText(sig + "\n" + found.joinToString("\n") { it.descriptor } + "\n")
            } catch (_: Throwable) {}
            return methods
        }
    }
}
