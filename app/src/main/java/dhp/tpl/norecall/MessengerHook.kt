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
 * 1. MsysToGraphqlMessageListSnapshotAdapter: forces `is_unsent` in the CQL message list to false/null
 *    so recalling messages never hides the text or switches to tombstone.
 * 2. TAM / Armadillo / E2EE Mailbox client unsend (`runTamClientMessageUnsend`)
 * 3. Minos / GraphQL recall delta applicator (`FbnMICoreGqlRecallMessageDeltaApplicatorFactory`)
 * 4. Message UI renderer model (`isUnsent` checks)
 */
object MessengerHook {

    const val TARGET = "com.facebook.orca"
    private const val TAG = Resolver.TAG

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        hookMsysAdapter(lp, modulePath)
        hookTamUnsend(lp, modulePath)
        hookRecallDelta(lp, modulePath)
        hookUiMessageModel(lp, modulePath)
    }

    /**
     * Hook 1: MsysToGraphqlMessageListSnapshotAdapter.
     * When messages are loaded from SQLite / Mailbox into the GraphQL model for display,
     * this adapter reads the flat CQL table.
     * We hook the adapter and force any message marked with isUnsent back to false,
     * restoring its original text, photo, audio, and attachments!
     */
    private fun hookMsysAdapter(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val methods = Resolver.resolve(lp, modulePath, "msys_adapter", 1) { bridge ->
            val results = bridge.findMethod {
                matcher {
                    usingStrings("MsysToGraphqlMessageListSnapshotAdapter.convertFlatMessagesToGraphqlModel")
                }
            }
            if (results.isEmpty()) null else listOf(results.first())
        }

        if (methods == null) {
            XposedBridge.log("$TAG: [Adapter] Msys adapter not found")
            return
        }

        val method = methods[0]
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val list = param.result as? List<*> ?: return
                for (item in list) {
                    if (item == null) continue
                    forceMessageActive(item)
                }
            }
        })
        XposedBridge.log("$TAG: [Adapter] Hooked ${method.declaringClass.name}.${method.name}")
    }

    /**
     * Hook 2: Modern Messenger E2EE (TAM / Armadillo) Mailbox unsend command.
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
                param.result = null
                XposedBridge.log("$TAG: [TAM] Intercepted & blocked runTamClientMessageUnsend!")
            }
        })
        XposedBridge.log("$TAG: [TAM] Hooked ${method.declaringClass.name}.${method.name}")
    }

    /**
     * Hook 3: Minos / GraphQL Recall Delta Applicator.
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
     * Hook 4: UI Renderer isUnsent Check.
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
                    forceMessageActive(arg)
                }
            }
        })
        XposedBridge.log("$TAG: [UI] Hooked ${method.declaringClass.name}.${method.name}")
    }

    /**
     * Helper to clear tombstone and isUnsent flags from any message model.
     */
    private fun forceMessageActive(obj: Any) {
        val clazz = obj.javaClass
        for (m in clazz.methods) {
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
