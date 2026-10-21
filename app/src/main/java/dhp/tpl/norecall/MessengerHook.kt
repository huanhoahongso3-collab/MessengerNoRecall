package dhp.tpl.norecall

import android.app.Notification
import android.app.NotificationManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Messenger Anti-Unsend / Anti-Recall Hook (v2.5)
 *
 * Strategy:
 *  - Cache message text keyed by stable MID / OTID strings (never 13-digit timestamps)
 *  - Hook TombstoneMessage constructors to inject "🔴 [Recalled]: <original>" into snippet
 *  - Fallback: notification cache + sender-name lookup
 *  - DB scan: read local Messenger SQLite on startup, filter IDs through isStableMessageId
 *  - No model substitution in row-builder (caused random-message bug)
 */
object MessengerHook {

    const val TARGET = "com.facebook.orca"
    private const val TAG = Resolver.TAG

    private var cacheFile: File? = null
    private val diskExecutor = Executors.newSingleThreadScheduledExecutor()
    private val saveScheduled = AtomicBoolean(false)

    // Per-message-ID text cache (MID / OTID / stable numeric ID -> display text)
    private val messageCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(10000, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 10000
        }
    )

    // Sender-name -> last seen message text (from notifications)
    private val notifCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(500, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 500
        }
    )

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        XposedBridge.log("$TAG: Installing v2.5 hooks for ${lp.packageName}...")

        initPersistentCache(lp)

        // DB scan on background thread – never blocks Messenger's UI or send path
        Thread({
            try {
                Thread.sleep(2500)
                scanLocalDatabases(lp)
            } catch (_: Throwable) {}
        }, "NoUnsent-DbScanner").start()

        hookNotifications()
        hookMessageModels(lp, modulePath)
        hookTombstoneModel(lp, modulePath)
    }

    // ─── Persistent cache ────────────────────────────────────────────────────

    private fun initPersistentCache(lp: XC_LoadPackage.LoadPackageParam) {
        try {
            val file = File(lp.appInfo.dataDir, "nounsent_cache.json")
            cacheFile = file
            if (file.exists()) {
                val json = JSONObject(file.readText())
                json.optJSONObject("messages")?.let { obj ->
                    for (k in obj.keys()) {
                        // Only re-load stable IDs – discard any legacy timestamp keys
                        if (isStableMessageId(k)) messageCache[k] = obj.getString(k)
                    }
                }
                json.optJSONObject("notifs")?.let { obj ->
                    for (k in obj.keys()) notifCache[k] = obj.getString(k)
                }
                XposedBridge.log("$TAG: [Cache] Loaded ${messageCache.size} messages, ${notifCache.size} notifs from disk")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [Cache] Load error: $t")
        }
    }

    private fun scheduleDiskSave() {
        if (saveScheduled.compareAndSet(false, true)) {
            diskExecutor.schedule({
                saveScheduled.set(false)
                doSaveToDisk()
            }, 4, TimeUnit.SECONDS)
        }
    }

    private fun doSaveToDisk() {
        try {
            val file = cacheFile ?: return
            val root = JSONObject()
            val msgs = JSONObject()
            synchronized(messageCache) { for ((k, v) in messageCache) msgs.put(k, v) }
            val notifs = JSONObject()
            synchronized(notifCache) { for ((k, v) in notifCache) notifs.put(k, v) }
            root.put("messages", msgs)
            root.put("notifs", notifs)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        } catch (_: Throwable) {}
    }

    private fun putMsg(id: String, text: String) {
        if (id.isBlank() || text.isBlank()) return
        if (!isStableMessageId(id)) return   // hard guard: never store timestamps as keys
        if (messageCache.put(id, text) != text) scheduleDiskSave()
    }

    private fun putNotif(sender: String, text: String) {
        if (sender.isBlank() || text.isBlank()) return
        if (notifCache.put(sender, text) != text) scheduleDiskSave()
    }

    // ─── Database scanner ────────────────────────────────────────────────────

    private fun scanLocalDatabases(lp: XC_LoadPackage.LoadPackageParam) {
        try {
            val dbDir = File(lp.appInfo.dataDir, "databases")
            if (!dbDir.exists()) return
            for (file in dbDir.listFiles() ?: return) {
                if (!file.isFile || file.length() < 1024) continue
                val n = file.name
                if (n.endsWith("-wal") || n.endsWith("-journal") || n.endsWith("-shm")) continue
                if (!n.contains("reverb") && !n.contains("msys") && !n.contains("threads") && !n.endsWith(".db")) continue
                try {
                    val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val tc = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                    val tables = mutableListOf<String>()
                    while (tc.moveToNext()) tables.add(tc.getString(0))
                    tc.close()
                    for (table in tables) {
                        if (n.contains("reverb")) { scanReverbTable(n, db, table); continue }
                        if (!table.contains("message", ignoreCase = true)) continue
                        scanMessageTable(n, db, table)
                    }
                    db.close()
                } catch (_: Throwable) {}
            }
            if (messageCache.isNotEmpty()) scheduleDiskSave()
        } catch (_: Throwable) {}
    }

    private fun scanMessageTable(dbName: String, db: SQLiteDatabase, table: String) {
        try {
            val cc = db.rawQuery("PRAGMA table_info($table)", null)
            val cols = mutableListOf<String>()
            while (cc.moveToNext()) cols.add(cc.getString(1))
            cc.close()

            val textCols = cols.filter {
                it.equals("text", true) || it.equals("body", true) ||
                it.equals("snippet", true) || it.equals("message_text", true)
            }
            if (textCols.isEmpty()) return

            // Only columns that could contain MID-style or OTID IDs
            val idCols = cols.filter {
                it.contains("mid", true) || it.contains("otid", true) ||
                it.equals("message_id", true) || it.equals("msg_id", true)
            }
            if (idCols.isEmpty()) return

            val q = db.rawQuery("SELECT * FROM $table LIMIT 5000", null)
            var count = 0
            while (q.moveToNext()) {
                var textVal: String? = null
                for (tc in textCols) {
                    val idx = q.getColumnIndex(tc); if (idx == -1 || q.isNull(idx)) continue
                    val v = q.getString(idx) ?: continue
                    if (v.isNotBlank()
                        && !v.contains("deleted a message", true)
                        && !v.contains("đã thu hồi", true)
                        && !v.contains("unsent a message", true)) {
                        textVal = v; break
                    }
                }
                if (textVal == null) continue
                for (ic in idCols) {
                    val idx = q.getColumnIndex(ic); if (idx == -1 || q.isNull(idx)) continue
                    val v = q.getString(idx) ?: continue
                    // CRITICAL: only store stable IDs, never timestamps
                    if (isStableMessageId(v)) putMsg(v, textVal)
                }
                count++
            }
            q.close()
            if (count > 0) XposedBridge.log("$TAG: [DB] $count msgs from $dbName.$table")
        } catch (_: Throwable) {}
    }

    private fun scanReverbTable(dbName: String, db: SQLiteDatabase, table: String) {
        if (!table.contains("persistence_store")) return
        try {
            val cc = db.rawQuery("PRAGMA table_info($table)", null)
            val cols = mutableListOf<String>()
            while (cc.moveToNext()) cols.add(cc.getString(1))
            cc.close()
            val otidCol = cols.firstOrNull { it.contains("offline_threading_id", true) } ?: return
            val payloadCol = cols.firstOrNull { it.contains("payload", true) } ?: return
            val q = db.rawQuery("SELECT $otidCol, $payloadCol FROM $table LIMIT 5000", null)
            var count = 0
            while (q.moveToNext()) {
                val otid = q.getString(0) ?: continue
                val text = extractPayloadText(q, 1) ?: continue
                if (otid.isNotBlank() && text.isNotBlank()) {
                    // OTID is typically a large numeric string — use isStableMessageId check
                    if (isStableMessageId(otid)) putMsg(otid, text)
                    count++
                }
            }
            q.close()
            if (count > 0) XposedBridge.log("$TAG: [Reverb] $count msgs from $dbName.$table")
        } catch (_: Throwable) {}
    }

    private fun extractPayloadText(c: Cursor, idx: Int): String? {
        return try {
            val blob = try { c.getBlob(idx) } catch (_: Throwable) { null }
            val s = if (blob != null) String(blob, Charsets.UTF_8) else c.getString(idx) ?: return null
            Regex(""""(?:text|body)"\s*:\s*"([^"]+)"""").find(s)?.groupValues?.get(1)
                ?: Regex("""[\p{L}\p{N}\p{P}\p{Z}]{4,}""").findAll(s)
                    .map { it.value.trim() }
                    .filter { !it.startsWith("mid.") && !it.startsWith("http") && !it.contains("com.facebook") && !it.all(Char::isDigit) }
                    .maxByOrNull { it.length }
        } catch (_: Throwable) { null }
    }

    // ─── Notification hook ────────────────────────────────────────────────────

    private fun hookNotifications() {
        try {
            for (method in NotificationManager::class.java.declaredMethods) {
                if (method.name != "notify" || !method.parameterTypes.contains(Notification::class.java)) continue
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val notif = param.args.firstOrNull { it is Notification } as? Notification ?: return
                        val extras = notif.extras ?: return
                        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
                        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: return
                        if (text.contains("recalled", true) || text.contains("thu hồi", true) ||
                            text.contains("deleted a message", true)) return  // skip recall notifs
                        putNotif(title, text)
                        if (title.contains(": ")) putNotif(title.substringAfter(": ").trim(), text)
                        val msgs = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                        if (msgs != null) for (m in msgs) {
                            if (m is Bundle) {
                                val mt = m.getCharSequence("text")?.toString()?.trim() ?: continue
                                val ms = m.getCharSequence("sender")?.toString()?.trim() ?: title
                                putNotif(ms, mt)
                            }
                        }
                    }
                })
            }
            XposedBridge.log("$TAG: [Notif] Hooked NotificationManager.notify")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [Notif] Hook error: $t")
        }
    }

    // ─── Message model hooks (text caching only) ──────────────────────────────

    private fun hookMessageModels(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        // Text messages: extract actual text
        hookModel(lp, modulePath, "text_class", "[TextMessage text=") { instance, clazz ->
            clazz.declaredFields.firstOrNull { CharSequence::class.java.isAssignableFrom(it.type) }
                ?.also { it.isAccessible = true }?.get(instance)?.toString()?.takeIf { it.isNotBlank() }
        }
        // Media – store a descriptive label so tombstones read "📷 [Photo]" etc.
        hookModel(lp, modulePath, "photo_class",    "[PhotoMessage photoCount=")      { _, _ -> "📷 [Photo]" }
        hookModel(lp, modulePath, "video_class",    "[VideoMessage videoAttachment=") { _, _ -> "🎥 [Video]" }
        hookModel(lp, modulePath, "document_class", "[DocumentMessage contentCount=") { _, _ -> "📁 [File]" }
        hookModel(lp, modulePath, "sticker_class",  "[StickerMessage stickerId=")    { _, _ -> "🏷️ [Sticker]" }
        hookModel(lp, modulePath, "audio_class",    "[AudioMessage uri=")             { _, _ -> "🎵 [Audio]" }
        hookModel(lp, modulePath, "gif_class",      "[LocalGifMessage photoMessage=") { _, _ -> "👾 [GIF]" }
        hookModel(lp, modulePath, "xma_class",      "[XmaMessage title=")             { _, _ -> "🔗 [Attachment]" }
    }

    private fun hookModel(
        lp: XC_LoadPackage.LoadPackageParam,
        modulePath: String,
        cacheKey: String,
        pattern: String,
        extractText: (instance: Any, clazz: Class<*>) -> String?
    ) {
        val cls = Resolver.resolveClass(lp, modulePath, cacheKey) { bridge ->
            bridge.findClass { matcher { usingStrings(pattern) } }.firstOrNull()
        } ?: run {
            XposedBridge.log("$TAG: [$cacheKey] class not found"); return
        }

        var hookedCount = 0
        for (ctor in cls.declaredConstructors) {
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val inst = param.thisObject ?: return
                    try {
                        val text = extractText(inst, cls) ?: return
                        // Index by MID and numeric IDs only – never by timestamp (timestamps collide!)
                        forEachMessageId(inst) { id -> putMsg(id, text) }
                    } catch (_: Throwable) {}
                }
            })
            hookedCount++
        }
        XposedBridge.log("$TAG: [$cacheKey] Hooked ${cls.name} ($hookedCount ctors)")
    }

    // ─── Tombstone hook ───────────────────────────────────────────────────────

    private fun hookTombstoneModel(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        val cls = Resolver.resolveClass(lp, modulePath, "tombstone_class") { bridge ->
            bridge.findClass { matcher { usingStrings("[TombstoneMessage snippet=") } }.firstOrNull()
        } ?: run {
            XposedBridge.log("$TAG: [Tombstone] class not found"); return
        }

        // Find the snippet field (String type) once, cache it
        val snippetField = cls.declaredFields.firstOrNull { it.type == String::class.java }
            ?: run { XposedBridge.log("$TAG: [Tombstone] no String field found"); return }
        snippetField.isAccessible = true

        var hookedCount = 0
        for (ctor in cls.declaredConstructors) {
            XposedBridge.hookMethod(ctor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val inst = param.thisObject ?: return
                    try {
                        val oldSnippet = snippetField.get(inst) as? String ?: return
                        if (oldSnippet.startsWith("🔴")) return  // already patched

                        // 1) Try to find original text via stable message IDs in the object
                        var cached: String? = null
                        forEachMessageId(inst) { id ->
                            if (cached == null) {
                                cached = messageCache[id]
                                if (cached != null) XposedBridge.log("$TAG: [Tombstone] Hit by id='$id'")
                            }
                        }

                        // 2) Fallback: parse sender name from snippet and look in notif cache
                        if (cached == null) {
                            val sender = extractSenderName(oldSnippet)
                            if (sender.isNotEmpty()) {
                                cached = notifCache[sender]
                                    ?: notifCache.entries.firstOrNull {
                                        it.key.contains(sender, true) || sender.contains(it.key, true)
                                    }?.value
                                if (cached != null) XposedBridge.log("$TAG: [Tombstone] Hit by sender='$sender'")
                            }
                        }

                        val newSnippet = if (cached != null) "🔴 [Recalled]: $cached" else "🔴 [Recalled] $oldSnippet"
                        snippetField.set(inst, newSnippet)
                        XposedBridge.log("$TAG: [Tombstone] '$oldSnippet' -> '$newSnippet'")
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: [Tombstone] Error: $t")
                    }
                }
            })
            hookedCount++
        }
        XposedBridge.log("$TAG: [Tombstone] Hooked ${cls.name} ($hookedCount ctors)")
    }

    /** Extract the sender name from a tombstone snippet like "Alice deleted a message" */
    private fun extractSenderName(snippet: String): String {
        return snippet
            .substringBefore(" deleted a message")
            .substringBefore(" đã thu hồi")
            .substringBefore(" removed a message")
            .substringBefore(" recalled a message")
            .substringBefore(" unsent a message")
            .trim()
    }

    // ─── ID extraction (MID and numeric message-ID only, NOT timestamps) ──────

    /**
     * Calls [block] for each stable message identifier found in [obj].
     *
     * We ONLY collect:
     *  - Strings starting with "mid." / "mid$" / "m_"  (MID format)
     *  - Short pure-digit strings 8–20 chars long (numeric message ID / otid)
     *    EXCLUDING 13-digit epoch-ms timestamps
     */
    private fun forEachMessageId(obj: Any, block: (String) -> Unit) {
        scanObj(obj, block, 0)
    }

    private fun scanObj(obj: Any?, block: (String) -> Unit, depth: Int) {
        if (obj == null || depth > 1) return
        var cls: Class<*>? = obj.javaClass
        while (cls != null && cls != Any::class.java) {
            val cn = cls.name
            if (cn.startsWith("java.") || cn.startsWith("android.") || cn.startsWith("kotlin.")) break
            for (f in cls.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    when (v) {
                        is String -> if (isStableMessageId(v)) block(v)
                        is Long   -> { /* skip – timestamps are not stable keys */ }
                        is Int    -> { /* skip */ }
                        else      -> if (depth == 0 && !v.javaClass.isPrimitive && !v.javaClass.isArray
                            && !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("android.")) {
                            scanObj(v, block, depth + 1)
                        }
                    }
                } catch (_: Throwable) {}
            }
            cls = cls.superclass
        }
    }

    /**
     * Returns true only for strings that are stable per-message identifiers,
     * i.e. NOT shared across different messages in the same conversation.
     *
     * Accepted:
     *  - "mid.*" / "m_*" strings (Messenger MID format)
     *  - Pure-digit strings of 8–20 chars, EXCLUDING 13-digit ms-epoch timestamps
     */
    fun isStableMessageId(s: String): Boolean {
        if (s.length < 4 || s.length > 64) return false
        // MID format: "mid.XXXXXXXXX" or "m_XXXXXXXXX"
        if (s.startsWith("mid.") || s.startsWith("mid$") || s.startsWith("m_")) return true
        // Numeric message / OTID: 8–20 digits.
        // Exclude 13-digit epoch timestamps (1_000_000_000_000 to 9_999_999_999_999)
        if (s.all(Char::isDigit)) {
            val len = s.length
            if (len < 8 || len > 20) return false
            if (len == 13) return false   // 13-digit = ms timestamp, too ambiguous
            return true
        }
        return false
    }
}
