package dhp.tpl.norecall

import android.app.Notification
import android.app.NotificationManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Messenger Anti-Unsend / Anti-Recall Hook.
 *
 * Intercepts incoming messages to cache their contents (from both notifications
 * and UI models), blocks incoming unsend commands, and restores original
 * message contents on recalled/deleted message items.
 */
object MessengerHook {

    private const val TAG = Resolver.TAG

    // Cache message content by message ID: id -> text
    private val messageCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(500, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 500
        }
    )

    // Cache recent notifications by sender: senderName -> text
    private val notifCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(100, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 100
        }
    )

    private val idRegex = Regex("""\bid=(\d+)""")

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        XposedBridge.log("$TAG: Installing anti-unsend hooks for ${lp.packageName} (process: ${lp.processName})...")
        hookNotifications()
        hookTextMessage(lp, modulePath)
        hookTombstoneModel(lp, modulePath)
        hookTamUnsend(lp, modulePath)
        hookRecallDelta(lp, modulePath)
    }

    /**
     * Intercepts system notifications posted by Messenger to capture message content
     * even before the chat is opened.
     */
    private fun hookNotifications() {
        try {
            val nmClass = NotificationManager::class.java
            for (method in nmClass.declaredMethods) {
                if (method.name == "notify" && method.parameterTypes.contains(Notification::class.java)) {
                    XposedBridge.hookMethod(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val notif = param.args.firstOrNull { it is Notification } as? Notification ?: return
                            val extras = notif.extras ?: return
                            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                            if (!title.isNullOrBlank() && !text.isNullOrBlank()) {
                                notifCache[title] = text
                                XposedBridge.log("$TAG: [NotifCache] Stored notification from '$title': '$text'")
                            }
                        }
                    })
                }
            }
            XposedBridge.log("$TAG: [Notif] Hooked NotificationManager.notify")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [Notif] Failed to hook NotificationManager: $t")
        }
    }

    /**
     * Hook 1: TextMessage Model Constructor.
     * Caches original message text mapped to the message ID.
     */
    private fun hookTextMessage(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val textClass = Resolver.resolveClass(lp, modulePath, "text_class") { bridge ->
            val results = bridge.findClass {
                matcher {
                    usingStrings("[TextMessage text=")
                }
            }
            results.firstOrNull()
        }

        if (textClass == null) {
            XposedBridge.log("$TAG: [TextMessage] not found via DexKit")
            return
        }

        for (constructor in textClass.declaredConstructors) {
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val instance = param.thisObject ?: return
                    try {
                        val id = extractMessageId(instance)
                        var text: String? = null
                        for (field in textClass.declaredFields) {
                            if (CharSequence::class.java.isAssignableFrom(field.type)) {
                                field.isAccessible = true
                                val v = field.get(instance)?.toString()
                                if (!v.isNullOrBlank()) {
                                    text = v
                                    break
                                }
                            }
                        }
                        if (id != null && text != null) {
                            messageCache[id] = text
                            XposedBridge.log("$TAG: [Cache] Saved text message (id=$id): '$text'")
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: [TextMessage] Error caching: $t")
                    }
                }
            })
        }
        XposedBridge.log("$TAG: [TextMessage] Hooked ${textClass.name} constructors")
    }

    /**
     * Hook 2: TombstoneMessage Model Constructor.
     * When a message is deleted, Messenger creates a TombstoneMessage with snippet="[Name] deleted a message".
     * We intercept this constructor and restore the original text or tag it visibly as recalled.
     */
    private fun hookTombstoneModel(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val tombstoneClass = Resolver.resolveClass(lp, modulePath, "tombstone_class") { bridge ->
            val results = bridge.findClass {
                matcher {
                    usingStrings("[TombstoneMessage snippet=")
                }
            }
            results.firstOrNull()
        }

        if (tombstoneClass == null) {
            XposedBridge.log("$TAG: [TombstoneModel] not found via DexKit")
            return
        }

        for (constructor in tombstoneClass.declaredConstructors) {
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val instance = param.thisObject ?: return
                    try {
                        val id = extractMessageId(instance)
                        for (field in tombstoneClass.declaredFields) {
                            if (field.type == String::class.java) {
                                field.isAccessible = true
                                val oldSnippet = field.get(instance) as? String ?: continue
                                if (oldSnippet.startsWith("🔴 [Recalled]")) break

                                val cachedText = if (id != null) messageCache[id] else null
                                val newSnippet = when {
                                    cachedText != null -> "🔴 [Recalled]: $cachedText"
                                    else -> {
                                        val sender = oldSnippet.substringBefore(" deleted")
                                            .substringBefore(" đã thu hồi")
                                            .trim()
                                        val notif = if (sender.isNotEmpty()) notifCache[sender] else null
                                        if (notif != null) "🔴 [Recalled]: $notif" else "🔴 [Recalled] $oldSnippet"
                                    }
                                }
                                field.set(instance, newSnippet)
                                XposedBridge.log("$TAG: [TombstoneModel] Intercepted tombstone (id=$id): '$oldSnippet' -> '$newSnippet'")
                                break
                            }
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: [TombstoneModel] Error overriding snippet: $t")
                    }
                }
            })
        }
        XposedBridge.log("$TAG: [TombstoneModel] Hooked ${tombstoneClass.name} constructors")
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
                param.result = null
                XposedBridge.log("$TAG: [TAM] Intercepted & blocked runTamClientMessageUnsend!")
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
                    usingStrings("applyMinosServerDeltas", "recallDeltaApplicator")
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

    private fun extractMessageId(instance: Any): String? {
        val str = instance.toString()
        val match = idRegex.find(str)
        if (match != null) return match.groupValues[1]

        for (field in instance.javaClass.declaredFields) {
            try {
                field.isAccessible = true
                val value = field.get(instance) ?: continue
                val fieldMatch = idRegex.find(value.toString())
                if (fieldMatch != null) return fieldMatch.groupValues[1]
            } catch (_: Throwable) {}
        }
        return null
    }
}
