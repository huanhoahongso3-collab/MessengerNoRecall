package dhp.tpl.norecall

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * Messenger Anti-Unsend / Anti-Recall Hook
 * 
 * Intercepts:
 * 1. TAM / Armadillo / E2EE Mailbox client unsend (`runTamClientMessageUnsend`)
 * 2. Minos / GraphQL recall delta applicator (`FbnMICoreGqlRecallMessageDeltaApplicatorFactory`)
 * 3. Message UI renderer (`isUnsent` / `adminMessageType` resolution)
 */
object MessengerHook {

    const val TARGET = "com.facebook.orca"
    private const val TAG = Resolver.TAG

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        hookTamUnsend(lp, modulePath)
        hookRecallDelta(lp, modulePath)
        hookUiMessageModel(lp, modulePath)
    }

    /**
     * Hook 1: Modern Messenger E2EE (TAM / Armadillo) Mailbox unsend command.
     * When a sender unsends, this method executes the local deletion in Mailbox.
     * Canceling this prevents the message from being removed or replaced with tombstone locally!
     */
    private fun hookTamUnsend(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val methods = Resolver.resolve(lp, modulePath, "tam_unsend", 1) { bridge ->
            val results = bridge.findMethod {
                matcher {
                    usingStrings("runTamClientMessageUnsend")
                }
            }
            if (results.isEmpty()) null else listOf(results.first())
        }

        if (methods == null) {
            XposedBridge.log("$TAG: [TAM] runTamClientMessageUnsend not found")
            return
        }

        val method = methods[0]
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Cancel local execution of TAM unsend!
                param.result = null
                XposedBridge.log("$TAG: [TAM] Intercepted & blocked runTamClientMessageUnsend!")
            }
        })
        XposedBridge.log("$TAG: [TAM] Hooked ${method.declaringClass.name}.${method.name}")
    }

    /**
     * Hook 2: Minos / GraphQL Recall Delta Applicator.
     * Handles traditional / cloud message recall payloads.
     */
    private fun hookRecallDelta(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val methods = Resolver.resolve(lp, modulePath, "recall_delta", 1) { bridge ->
            val results = bridge.findMethod {
                matcher {
                    usingStrings("FbnMICoreGqlRecallMessageDeltaApplicatorFactory")
                }
            }
            if (results.isEmpty()) null else listOf(results.first())
        }

        if (methods == null) {
            XposedBridge.log("$TAG: [Delta] Recall delta applicator not found")
            return
        }

        val method = methods[0]
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                param.result = null
                XposedBridge.log("$TAG: [Delta] Intercepted & blocked recall delta applicator!")
            }
        })
        XposedBridge.log("$TAG: [Delta] Hooked ${method.declaringClass.name}.${method.name}")
    }

    /**
     * Hook 3: UI Renderer isUnsent Check.
     * Forces `isUnsent` checks to false so UI doesn't render tombstone view for any message.
     */
    private fun hookUiMessageModel(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val methods = Resolver.resolve(lp, modulePath, "ui_msg_model", 1) { bridge ->
            val results = bridge.findMethod {
                matcher {
                    usingStrings("ig_detected_outcome", "isUnsent")
                }
            }
            if (results.isEmpty()) null else listOf(results.first())
        }

        if (methods == null) {
            XposedBridge.log("$TAG: [UI] Message model method not found")
            return
        }

        val method = methods[0]
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                for (arg in param.args) {
                    if (arg == null) continue
                    for (m in arg.javaClass.methods) {
                        if (m.parameterCount == 0 && (m.returnType == Boolean::class.java || m.returnType == java.lang.Boolean.TYPE)) {
                            val name = m.name.lowercase()
                            if (name.contains("unsent") || name.contains("tombstone")) {
                                try {
                                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                                        override fun afterHookedMethod(p: MethodHookParam) {
                                            p.result = false
                                        }
                                    })
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                }
            }
        })
        XposedBridge.log("$TAG: [UI] Hooked ${method.declaringClass.name}.${method.name}")
    }
}
