package dhp.tpl.norecall

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Messenger Anti-Unsend / Anti-Recall Hook with Detailed Debug Logging
 */
object MessengerHook {

    const val TARGET = "com.facebook.orca"
    private const val TAG = Resolver.TAG

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        XposedBridge.log("$TAG: Installing hooks for ${lp.packageName} (process: ${lp.processName})...")
        hookMessageTypeClassifier(lp, modulePath)
        hookMsysAdapter(lp, modulePath)
        hookTamUnsend(lp, modulePath)
        hookRecallDelta(lp, modulePath)
        hookUiMessageModel(lp, modulePath)
    }

    /**
     * Hook 1: Message Type Classifier (X.55y.A00).
     */
    private fun hookMessageTypeClassifier(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val methods = Resolver.resolve(lp, modulePath, "type_classifier", 1) { bridge ->
            val results = bridge.findMethod {
                matcher {
                    usingStrings("TombstoneMessage", "TextMessage", "PhotoMessage")
                }
            }
            if (results.isEmpty()) null else listOf(results.first())
        }

        if (methods == null) {
            XposedBridge.log("$TAG: [Classifier] Message type classifier not found")
            return
        }

        val method = methods[0]
        val returnType = method.returnType

        var constructor: Constructor<*>? = null
        try {
            constructor = returnType.getDeclaredConstructor(String::class.java).apply {
                isAccessible = true
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [Classifier] Constructor error: $t")
        }

        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val res = param.result ?: return
                val resStr = res.toString()
                
                // Detailed debug logging
                if (resStr.contains("Tombstone") || resStr.contains("Admin") || resStr.contains("Unavailable")) {
                    XposedBridge.log("$TAG: [Classifier-DEBUG] Intercepted classification: $resStr, arg: ${param.args.firstOrNull()}")
                    if (constructor != null) {
                        try {
                            param.result = constructor.newInstance("TextMessage")
                            XposedBridge.log("$TAG: [Classifier-DEBUG] Successfully returned new TextMessage object!")
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG: [Classifier-DEBUG] Instantiation failed: $t")
                        }
                    } else {
                        for (field in res.javaClass.declaredFields) {
                            field.isAccessible = true
                            val fVal = field.get(res)
                            if (fVal is String) {
                                field.set(res, "TextMessage")
                                XposedBridge.log("$TAG: [Classifier-DEBUG] Mutated field ${field.name} to TextMessage")
                            }
                        }
                    }
                }
            }
        })
        XposedBridge.log("$TAG: [Classifier] Hooked ${method.declaringClass.name}.${method.name}")
    }

    /**
     * Hook 2: MsysToGraphqlMessageListSnapshotAdapter (X.VIF.A0H).
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
                XposedBridge.log("$TAG: [Adapter-DEBUG] Processing snapshot of ${list.size} messages")
                for (item in list) {
                    if (item == null) continue
                    clearUnsentFlags(item)
                }
            }
        })
        XposedBridge.log("$TAG: [Adapter] Hooked ${method.declaringClass.name}.${method.name}")
    }

    /**
     * Hook 3: Modern Messenger E2EE (TAM / Armadillo) Mailbox unsend command.
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
                XposedBridge.log("$TAG: [TAM-DEBUG] runTamClientMessageUnsend CALLED! Args: ${param.args.joinToString()}")
                param.result = null
                XposedBridge.log("$TAG: [TAM-DEBUG] Canceled execution.")
            }
        })
        XposedBridge.log("$TAG: [TAM] Hooked ${method.declaringClass.name}.${method.name}")
    }

    /**
     * Hook 4: Minos / GraphQL Recall Delta Applicator.
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
                XposedBridge.log("$TAG: [Delta-DEBUG] Recall delta applicator CALLED! Args: ${param.args.joinToString()}")
                param.result = null
                XposedBridge.log("$TAG: [Delta-DEBUG] Canceled execution.")
            }
        })
        XposedBridge.log("$TAG: [Delta] Hooked ${method.declaringClass.name}.${method.name}")
    }

    /**
     * Hook 5: UI Renderer isUnsent Check.
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
                    clearUnsentFlags(arg)
                }
            }
        })
        XposedBridge.log("$TAG: [UI] Hooked ${method.declaringClass.name}.${method.name}")
    }

    private fun clearUnsentFlags(obj: Any) {
        val clazz = obj.javaClass
        for (m in clazz.methods) {
            if (m.parameterCount == 0 && (m.returnType == Boolean::class.java || m.returnType == java.lang.Boolean.TYPE)) {
                val name = m.name.lowercase()
                if (name.contains("unsent") || name.contains("tombstone")) {
                    try {
                        XposedBridge.hookMethod(m, object : XC_MethodHook() {
                            override fun afterHookedMethod(p: MethodHookParam) {
                                if (p.result == true) {
                                    XposedBridge.log("$TAG: [Flag-DEBUG] Overrode $name() from true to false on ${clazz.simpleName}")
                                }
                                p.result = false
                            }
                        })
                    } catch (_: Throwable) {}
                }
            }
        }
    }
}
