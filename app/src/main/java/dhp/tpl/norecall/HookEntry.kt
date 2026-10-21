package dhp.tpl.norecall

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed entry point.
 * Declared in assets/xposed_init so the Xposed framework loads this class.
 */
class HookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {

    private var modulePath = ""

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Only hook the main Messenger process, not service/push processes
        if (lpparam.packageName != MessengerHook.TARGET) return
        if (lpparam.processName != lpparam.packageName) return

        try {
            MessengerHook.install(lpparam, modulePath)
        } catch (t: Throwable) {
            XposedBridge.log("${Resolver.TAG}: fatal install error: $t")
        }
    }
}
