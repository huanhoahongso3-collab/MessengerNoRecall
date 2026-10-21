package dhp.tpl.norecall

import android.os.Build
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Method

/**
 * DexKit-backed method & class resolver with per-build APK caching.
 */
object Resolver {

    const val TAG = "MessengerNoUnsent"

    @Volatile private var dexkitLoaded = false

    /**
     * Find [count] methods matching [find], returning their [Method] reflections.
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
        val sig  = "${apk.length()}-${apk.lastModified()}"
        val cache = File(lp.appInfo.dataDir, "msgnounsent.$cacheKey.cache")

        try {
            val lines = cache.takeIf { it.exists() }?.readLines()
            if (lines != null && lines.size == count + 1 && lines[0] == sig) {
                return lines.drop(1).map { DexMethod(it).getMethodInstance(cl) }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: cache unusable [$cacheKey]: $t")
        }

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
            try {
                cache.writeText(sig + "\n" + found.joinToString("\n") { it.descriptor } + "\n")
            } catch (_: Throwable) {}
            return methods
        }
    }

    /**
     * Find class matching [find], returning its [Class] reflection.
     */
    fun resolveClass(
        lp: XC_LoadPackage.LoadPackageParam,
        modulePath: String,
        cacheKey: String,
        find: (DexKitBridge) -> ClassData?,
    ): Class<*>? {
        val cl  = lp.classLoader
        val apk = File(lp.appInfo.sourceDir)
        val sig  = "${apk.length()}-${apk.lastModified()}"
        val cache = File(lp.appInfo.dataDir, "msgnounsent.cls.$cacheKey.cache")

        try {
            val lines = cache.takeIf { it.exists() }?.readLines()
            if (lines != null && lines.size == 2 && lines[0] == sig) {
                return cl.loadClass(lines[1])
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: cache unusable [class:$cacheKey]: $t")
        }

        if (!dexkitLoaded) {
            System.load("$modulePath!/lib/${Build.SUPPORTED_ABIS[0]}/libdexkit.so")
            dexkitLoaded = true
        }

        DexKitBridge.create(apk.absolutePath).use { bridge ->
            val found = find(bridge) ?: run {
                XposedBridge.log("$TAG: fingerprint [class:$cacheKey] returned null")
                return null
            }
            val clazz = found.getInstance(cl)
            try {
                cache.writeText("$sig\n${found.name}\n")
            } catch (_: Throwable) {}
            return clazz
        }
    }
}
