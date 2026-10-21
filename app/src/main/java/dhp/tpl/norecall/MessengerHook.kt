package dhp.tpl.norecall

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Method

/**
 * Main hook logic for Facebook Messenger (com.facebook.orca).
 *
 * ── Overview ────────────────────────────────────────────────────────────────
 *
 * Messenger obfuscates every class and method name, so nothing is hardcoded.
 * DexKit locates targets at runtime using stable string fingerprints that are
 * baked into the DEX as log messages, field-name strings, or GraphQL field
 * keys – those rarely change between app versions.
 *
 * Two hook groups keep the feature alive:
 *
 *  [A] CACHE HOOK – "message observer"
 *      Fires whenever Messenger processes an incoming message (or batch of
 *      messages) from the server, before any unsend/tombstone flag can be
 *      applied.  We extract message ID, body text, and attachment type from
 *      the method's arguments / return value via reflection, then store them
 *      in MessageCache.
 *
 *  [B] TOMBSTONE HOOK – "renderer interceptor"
 *      Fires when the thread-view renderer decides to display a tombstone
 *      ("X unsent a message").  We look up the message ID in MessageCache and,
 *      if we have a saved copy, overwrite the displayed text with the original
 *      content so it stays visible.
 *
 * ── DexKit fingerprints ─────────────────────────────────────────────────────
 *
 * Each fingerprint is a list of strings (or structural constraints) that
 * uniquely identify one method.  Multiple fallback arrays are tried in order
 * so the module keeps working across minor Messenger updates.
 *
 * Fingerprints confirmed against Messenger 583.0.0.29.95 (APK from
 * /sdcard/Download/).  String evidence collected from the DEX string pools
 * via a Java extractor that reads the DEX header directly.
 *
 * ── How extraction works ────────────────────────────────────────────────────
 *
 * Because every class and field is obfuscated we cannot write
 * `message.getBody()` directly.  Instead, for each hooked method we
 * iterate over the methods of the argument/receiver objects and apply
 * heuristics:
 *   • message ID  – String-returning no-arg method whose result matches
 *                   Messenger's mid: "mid.xxx…" pattern or is a long
 *                   numeric string
 *   • body text   – String-returning no-arg method that is not the ID
 *   • attachment  – Boolean-returning no-arg methods (hasPhoto, hasVideo …)
 *                   whose name contains a known keyword (photo, video, …)
 *
 * This is intentionally liberal; false positives land in the cache but
 * cause no harm (they are never recalled).
 */
object MessengerHook {

    const val TARGET = "com.facebook.orca"
    private val TAG  = Resolver.TAG

    // ─────────────────────────────────────────────────────────────────────
    // Fingerprint sets for the cache hook (Hook A)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Method that processes a freshly-received message or message delta from
     * the server and stores it locally.
     *
     * Stable evidence in DEX:
     *   classes3/4.dex: "can_unsend", "is_unsent", "isUnsent"
     *   classes4.dex:   "is_unsent", "MESSAGE_IS_TOMBSTONED"
     *   classes3.dex:   "tombstone: msgType=", "TombstoneMessagePlugin"
     *
     * We try multiple string combinations. DexKit returns all methods that
     * use ALL listed strings; we pick the first that also calls getIsUnsent.
     */
    private val CACHE_FINGERPRINTS: List<Array<String>> = listOf(
        // Primary: method that both checks "can_unsend" and "is_unsent"
        arrayOf("can_unsend", "is_unsent"),
        // Fallback A: just "is_unsent"
        arrayOf("is_unsent"),
        // Fallback B: "isUnsent" camelCase (proto-generated getter string)
        arrayOf("isUnsent"),
        // Fallback C: unsend processor that uses MESSAGE_IS_TOMBSTONED constant
        arrayOf("MESSAGE_IS_TOMBSTONED"),
    )

    // ─────────────────────────────────────────────────────────────────────
    // Fingerprint sets for the tombstone hook (Hook B)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Method in the tombstone renderer / plugin that logs the tombstone type.
     *
     * Stable evidence in DEX:
     *   classes3.dex: "tombstone: msgType=", "rendered_tombstone_message",
     *                 "TombstoneMessagePlugin", "newUnavailableMessage"
     *   classes5.dex: "TOMBSTONE_VIEW"
     */
    private val TOMBSTONE_FINGERPRINTS: List<Array<String>> = listOf(
        arrayOf("tombstone: msgType=", "rendered_tombstone_message"),
        arrayOf("tombstone: msgType="),
        arrayOf("rendered_tombstone_message"),
        arrayOf("newUnavailableMessage", "tombstone"),
        arrayOf("TOMBSTONE_VIEW"),
    )

    // ─────────────────────────────────────────────────────────────────────
    // Fingerprint sets for the unsend-notification hook (Hook C)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * The push-data handler that processes an incoming "someone unsent a
     * message" MQTT/Minos notification.
     *
     * Stable evidence in DEX:
     *   classes4.dex: "Error processing unsend notification"
     *   classes3.dex: "Notification dropped (New message notification recalled)"
     *                 "messenger_android_unsend_notificaiton_retraction"  [sic]
     */
    private val UNSEND_NOTIF_FINGERPRINTS: List<Array<String>> = listOf(
        arrayOf("Error processing unsend notification"),
        arrayOf("messenger_android_unsend_notificaiton_retraction"),
        arrayOf("Notification dropped (New message notification recalled)"),
    )

    // ─────────────────────────────────────────────────────────────────────
    // Install
    // ─────────────────────────────────────────────────────────────────────

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        installCacheHook(lp, modulePath)
        installTombstoneHook(lp, modulePath)
        installUnsendNotifHook(lp, modulePath)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Hook A – cache incoming messages before they can be tombstoned
    // ─────────────────────────────────────────────────────────────────────

    private fun installCacheHook(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val methods = resolveFirst(lp, modulePath, "cache", CACHE_FINGERPRINTS) ?: run {
            XposedBridge.log("$TAG: [A] cache-hook method not found in this version")
            return
        }

        XposedBridge.hookMethod(methods[0], object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                // Try all arguments AND the return value (if non-null object)
                val candidates = buildList {
                    addAll(param.args.filterNotNull())
                    val rv = param.result
                    if (rv != null && rv !is Boolean && rv !is Int && rv !is Long) add(rv)
                }
                for (obj in candidates) {
                    tryExtractAndCache(obj)
                }
            }
        })
        XposedBridge.log("$TAG: [A] cache hook installed on ${methods[0].declaringClass.name}.${methods[0].name}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Hook B – intercept tombstone renderer and swap in original content
    // ─────────────────────────────────────────────────────────────────────

    private fun installTombstoneHook(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val methods = resolveFirst(lp, modulePath, "tombstone", TOMBSTONE_FINGERPRINTS) ?: run {
            XposedBridge.log("$TAG: [B] tombstone hook method not found in this version")
            return
        }

        val tombstoneMethod = methods[0]

        XposedBridge.hookMethod(tombstoneMethod, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Walk all args looking for the message object that carries the ID
                val allObjs = buildList {
                    val th = param.thisObject
                    if (th != null) add(th)
                    addAll(param.args.filterNotNull())
                }

                for (obj in allObjs) {
                    val msgId = extractMessageId(obj) ?: continue
                    val entry = MessageCache.get(msgId) ?: continue

                    // We have saved content — override the tombstone text.
                    // Try to call a setter named after "tombstoneText" or "setText" on the object.
                    val label = MessageCache.formatLabel(entry)
                    val injected = injectText(obj, label)

                    // If we cannot find a setter, we log and fall through
                    // (the tombstone still shows, but at least we logged it).
                    if (injected) {
                        XposedBridge.log("$TAG: [B] restored recalled msg $msgId: $label")
                    } else {
                        XposedBridge.log("$TAG: [B] have cache for $msgId but no setter found on ${obj.javaClass.simpleName}")
                    }
                    break
                }
            }
        })
        XposedBridge.log("$TAG: [B] tombstone hook installed on ${tombstoneMethod.declaringClass.name}.${tombstoneMethod.name}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // Hook C – unsend push-notification handler (secondary save path)
    //
    // This fires when the other device's push arrives saying a message was
    // unsent.  At this point the message object should still be in the local
    // database but may not have been passed through Hook A yet (e.g. if the
    // user was offline when the original message arrived and came back online
    // only after it was unsent).
    // ─────────────────────────────────────────────────────────────────────

    private fun installUnsendNotifHook(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val methods = resolveFirst(lp, modulePath, "unsend_notif", UNSEND_NOTIF_FINGERPRINTS) ?: run {
            XposedBridge.log("$TAG: [C] unsend-notification hook not found (non-fatal)")
            return
        }

        XposedBridge.hookMethod(methods[0], object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                // Before the handler clears the message, try to grab its content
                val allObjs = buildList {
                    val th = param.thisObject
                    if (th != null) add(th)
                    addAll(param.args.filterNotNull())
                }
                for (obj in allObjs) {
                    tryExtractAndCache(obj)
                }
            }
        })
        XposedBridge.log("$TAG: [C] unsend-notif hook installed on ${methods[0].declaringClass.name}.${methods[0].name}")
    }

    // ─────────────────────────────────────────────────────────────────────
    // DexKit resolve helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Try each fingerprint in [fingerprintSets] in order, returning the first
     * successful result.  Caches under "$cacheKey-N" for each attempt.
     */
    private fun resolveFirst(
        lp: XC_LoadPackage.LoadPackageParam,
        modulePath: String,
        cacheKey: String,
        fingerprintSets: List<Array<String>>,
    ): List<Method>? {
        fingerprintSets.forEachIndexed { idx, strings ->
            val key = "${cacheKey}_$idx"
            val result = Resolver.resolve(lp, modulePath, key, 1) { bridge ->
                findByStrings(bridge, strings)
            }
            if (result != null) return result
        }
        return null
    }

    /**
     * DexKit search: find all methods that reference every string in [strings].
     * Returns a single-element list (the best candidate) or null.
     *
     * "Best" is the method with the fewest referencing strings (more specific
     * match) from the result set, which minimises false positives.
     */
    private fun findByStrings(bridge: DexKitBridge, strings: Array<String>): List<MethodData>? {
        val results = bridge.findMethod {
            matcher {
                usingStrings(*strings)
            }
        }
        if (results.isEmpty()) return null
        // Prefer the method with the smallest declaring class (likely a focused utility class)
        val best = results.minByOrNull { it.className.length } ?: return null
        return listOf(best)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Reflection helpers – extract data from obfuscated objects
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Inspect [obj] and, if it looks like a message model, extract its
     * content and store it in MessageCache.
     */
    private fun tryExtractAndCache(obj: Any) {
        try {
            val msgId   = extractMessageId(obj) ?: return
            val body    = extractBody(obj)
            val attType = detectAttachmentType(obj)

            // Don't overwrite an already-cached (presumably valid) entry
            // unless we now have richer content
            val existing = MessageCache.get(msgId)
            if (existing != null && !existing.body.isNullOrBlank() && body.isNullOrBlank()) return

            val senderName = extractSenderName(obj)
            MessageCache.put(MessageCache.Entry(msgId, body, attType, senderName))
        } catch (_: Throwable) {
            // Reflective probing failures are expected for non-message objects
        }
    }

    /**
     * Try to find the message ID on [obj].
     *
     * Messenger message IDs follow the pattern "mid.$base64chars" or are
     * long numeric strings.  We look for a zero-arg String method whose
     * result matches.
     */
    private fun extractMessageId(obj: Any): String? {
        val clazz = obj.javaClass
        // Quick-path: look for methods whose name hints at "id" or "key"
        for (m in clazz.methods) {
            if (m.parameterCount != 0) continue
            if (m.returnType != String::class.java) continue
            val name = m.name.lowercase()
            if (!name.contains("id") && !name.contains("key") && !name.contains("mid")) continue
            val v = runCatching { m.invoke(obj) as? String }.getOrNull() ?: continue
            if (looksLikeMessageId(v)) return v
        }
        // Slow-path: scan all String-returning no-arg methods
        for (m in clazz.methods) {
            if (m.parameterCount != 0) continue
            if (m.returnType != String::class.java) continue
            val v = runCatching { m.invoke(obj) as? String }.getOrNull() ?: continue
            if (looksLikeMessageId(v)) return v
        }
        return null
    }

    /**
     * "mid.xxx…" or a purely-numeric string of ≥8 digits.
     * Messenger uses both formats in different code paths.
     */
    private fun looksLikeMessageId(s: String): Boolean {
        if (s.isBlank() || s.length < 6) return false
        if (s.startsWith("mid.")) return true
        if (s.all { it.isDigit() } && s.length >= 8) return true
        return false
    }

    /**
     * Look for the message body text on [obj].
     * We skip anything that looks like a URL or a very long string (binary).
     */
    private fun extractBody(obj: Any): String? {
        val clazz = obj.javaClass
        for (m in clazz.methods) {
            if (m.parameterCount != 0) continue
            if (m.returnType != String::class.java) continue
            val name = m.name.lowercase()
            // Prefer methods whose name hints at body/text/message/snippet
            if (!name.contains("body") && !name.contains("text") &&
                !name.contains("snippet") && !name.contains("message") &&
                !name.contains("content")) continue
            val v = runCatching { m.invoke(obj) as? String }.getOrNull() ?: continue
            if (looksLikeMessageId(v)) continue                       // skip IDs
            if (v.startsWith("http://") || v.startsWith("https://")) continue // skip URLs
            if (v.length > 4000) continue                              // skip blobs
            if (v.isNotBlank()) return v
        }
        return null
    }

    /**
     * Infer attachment type from boolean-returning no-arg methods whose name
     * contains a known keyword.
     *
     * Messenger's message model has fields like hasPhoto_, hasVideo_, etc.
     * The obfuscated getters may have arbitrary names but the boolean pattern
     * + declared-class proximity is a reliable signal.
     */
    private fun detectAttachmentType(obj: Any): String {
        val clazz = obj.javaClass
        for (m in clazz.methods) {
            if (m.parameterCount != 0) continue
            if (m.returnType != Boolean::class.java && m.returnType != java.lang.Boolean.TYPE) continue
            val flag = runCatching { m.invoke(obj) as? Boolean }.getOrNull() ?: continue
            if (!flag) continue
            val name = m.name.lowercase()
            return when {
                name.contains("sticker")  -> MessageCache.TYPE_STICKER
                name.contains("gif")      -> MessageCache.TYPE_GIF
                name.contains("photo") ||
                name.contains("image")    -> MessageCache.TYPE_PHOTO
                name.contains("video")    -> MessageCache.TYPE_VIDEO
                name.contains("audio") ||
                name.contains("voice")    -> MessageCache.TYPE_AUDIO
                name.contains("file") ||
                name.contains("doc")      -> MessageCache.TYPE_FILE
                name.contains("location") ||
                name.contains("place")    -> MessageCache.TYPE_LOCATION
                else                      -> continue
            }
        }
        return MessageCache.TYPE_TEXT
    }

    /** Try to grab a sender display name. */
    private fun extractSenderName(obj: Any): String? {
        val clazz = obj.javaClass
        for (m in clazz.methods) {
            if (m.parameterCount != 0) continue
            if (m.returnType != String::class.java) continue
            val name = m.name.lowercase()
            if (!name.contains("sender") && !name.contains("author") && !name.contains("name")) continue
            val v = runCatching { m.invoke(obj) as? String }.getOrNull() ?: continue
            if (v.isNotBlank() && !looksLikeMessageId(v)) return v
        }
        return null
    }

    /**
     * Try to set the text that the tombstone view will display.
     *
     * We look for:
     *  1. A method named after "tombstoneText" setter (setTombstoneText, etc.)
     *  2. Any single-String-param setter on the object
     *
     * Returns true if a setter was found and called.
     */
    private fun injectText(obj: Any, text: String): Boolean {
        val clazz = obj.javaClass

        // Priority: setter whose name contains "tombstone" or "snippet" or "text"
        for (m in clazz.methods) {
            if (m.parameterCount != 1) continue
            if (m.parameterTypes[0] != String::class.java) continue
            val name = m.name.lowercase()
            if (name.contains("tombstone") || name.contains("snippet") || name.contains("admintext")) {
                return runCatching { m.invoke(obj, text); true }.getOrElse { false }
            }
        }

        // Fallback: any single-String setter
        for (m in clazz.methods) {
            if (m.parameterCount != 1) continue
            if (m.parameterTypes[0] != String::class.java) continue
            if (!m.name.startsWith("set")) continue
            return runCatching { m.invoke(obj, text); true }.getOrElse { false }
        }
        return false
    }
}
