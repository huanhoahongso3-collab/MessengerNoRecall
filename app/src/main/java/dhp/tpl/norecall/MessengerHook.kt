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

/**
 * Messenger Anti-Unsend / Anti-Recall Hook with Reverb Deleted Messages Scanner,
 * Multi-Key Identification, and Persistent Disk Cache.
 */
object MessengerHook {

    const val TARGET = "com.facebook.orca"
    private const val TAG = Resolver.TAG

    private var cacheFile: File? = null

    // Multi-key message cache: (ID, MID, timestamp, otid) -> text
    private val messageCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(10000, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 10000
        }
    )

    // Sender -> latest message text
    private val notifCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(500, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 500
        }
    )

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        XposedBridge.log("$TAG: Installing anti-unsend hooks for ${lp.packageName} (process: ${lp.processName})...")

        initPersistentCache(lp)
        scanLocalDatabases(lp)

        hookNotifications()
        hookMessageModels(lp, modulePath)
        hookTombstoneModel(lp, modulePath)
    }

    private fun initPersistentCache(lp: XC_LoadPackage.LoadPackageParam) {
        try {
            val file = File(lp.appInfo.dataDir, "nounsent_cache.json")
            cacheFile = file
            if (file.exists()) {
                val json = JSONObject(file.readText())
                val messagesObj = json.optJSONObject("messages")
                if (messagesObj != null) {
                    for (key in messagesObj.keys()) {
                        messageCache[key] = messagesObj.getString(key)
                    }
                }
                val notifObj = json.optJSONObject("notifs")
                if (notifObj != null) {
                    for (key in notifObj.keys()) {
                        notifCache[key] = notifObj.getString(key)
                    }
                }
                XposedBridge.log("$TAG: [DiskCache] Loaded ${messageCache.size} messages, ${notifCache.size} notifs from disk")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [DiskCache] Failed to load cache: $t")
        }
    }

    @Synchronized
    private fun saveToDisk() {
        try {
            val file = cacheFile ?: return
            val json = JSONObject()
            val messagesObj = JSONObject()
            synchronized(messageCache) {
                for ((k, v) in messageCache) {
                    messagesObj.put(k, v)
                }
            }
            json.put("messages", messagesObj)

            val notifObj = JSONObject()
            synchronized(notifCache) {
                for ((k, v) in notifCache) {
                    notifObj.put(k, v)
                }
            }
            json.put("notifs", notifObj)

            file.writeText(json.toString())
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [DiskCache] Failed to save cache: $t")
        }
    }

    private fun putMessage(id: String, text: String) {
        if (id.isBlank() || text.isBlank()) return
        val prev = messageCache.put(id, text)
        if (prev != text) {
            saveToDisk()
        }
    }

    private fun putNotif(sender: String, text: String) {
        if (sender.isBlank() || text.isBlank()) return
        val prev = notifCache.put(sender, text)
        if (prev != text) {
            saveToDisk()
        }
    }

    /**
     * Scan Messenger local SQLite databases including reverb_db (which stores deleted messages!),
     * msys_database, and threads_db2.
     */
    private fun scanLocalDatabases(lp: XC_LoadPackage.LoadPackageParam) {
        try {
            val dbDir = File(lp.appInfo.dataDir, "databases")
            if (!dbDir.exists()) return
            val files = dbDir.listFiles() ?: return

            for (file in files) {
                if (!file.isFile || file.length() < 1024) continue
                val name = file.name
                if (name.endsWith("-wal") || name.endsWith("-journal") || name.endsWith("-shm")) continue
                if (!name.contains("reverb") && !name.contains("msys") && !name.contains("threads") && !name.endsWith(".db")) continue

                try {
                    val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                    val tables = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        tables.add(cursor.getString(0))
                    }
                    cursor.close()

                    for (table in tables) {
                        // Scan reverb_db tables (where deleted messages are stored!)
                        if (name.contains("reverb")) {
                            scanReverbTable(file.name, db, table)
                            continue
                        }

                        if (!table.contains("message", ignoreCase = true)) continue

                        val colCursor = db.rawQuery("PRAGMA table_info($table)", null)
                        val cols = mutableListOf<String>()
                        while (colCursor.moveToNext()) {
                            cols.add(colCursor.getString(1))
                        }
                        colCursor.close()

                        val textCols = cols.filter {
                            it.equals("text", ignoreCase = true) ||
                            it.equals("body", ignoreCase = true) ||
                            it.equals("snippet", ignoreCase = true) ||
                            it.equals("message_text", ignoreCase = true)
                        }
                        if (textCols.isEmpty()) continue

                        val idCols = cols.filter {
                            it.contains("id", ignoreCase = true) ||
                            it.contains("mid", ignoreCase = true) ||
                            it.contains("otid", ignoreCase = true) ||
                            it.contains("timestamp", ignoreCase = true) ||
                            it.contains("time", ignoreCase = true)
                        }

                        val q = db.rawQuery("SELECT * FROM $table", null)
                        var count = 0
                        while (q.moveToNext()) {
                            var textVal: String? = null
                            for (tc in textCols) {
                                val idx = q.getColumnIndex(tc)
                                if (idx != -1 && !q.isNull(idx)) {
                                    val v = q.getString(idx)
                                    if (!v.isNullOrBlank() &&
                                        !v.contains("deleted a message", ignoreCase = true) &&
                                        !v.contains("đã thu hồi", ignoreCase = true)) {
                                        textVal = v
                                        break
                                    }
                                }
                            }
                            if (textVal == null) continue

                            for (ic in idCols) {
                                val idx = q.getColumnIndex(ic)
                                if (idx != -1 && !q.isNull(idx)) {
                                    val v = q.getString(idx)
                                    if (!v.isNullOrBlank() && v != textVal) {
                                        messageCache[v] = textVal
                                    }
                                }
                            }
                            count++
                        }
                        q.close()
                        if (count > 0) {
                            XposedBridge.log("$TAG: [DB] Pre-loaded $count messages from ${file.name}.$table")
                        }
                    }
                    db.close()
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG: [DB] Could not scan ${file.name}: $t")
                }
            }
            if (messageCache.isNotEmpty()) {
                saveToDisk()
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [DB] Database scan error: $t")
        }
    }

    /**
     * Scans reverb_db tables (specifically local_message_persistence_store_deleted_messages
     * and local_message_persistence_store) to recover payloads of deleted messages!
     */
    private fun scanReverbTable(dbName: String, db: SQLiteDatabase, table: String) {
        if (!table.contains("persistence_store")) return
        try {
            val colCursor = db.rawQuery("PRAGMA table_info($table)", null)
            val cols = mutableListOf<String>()
            while (colCursor.moveToNext()) {
                cols.add(colCursor.getString(1))
            }
            colCursor.close()

            val otidCol = cols.firstOrNull { it.contains("offline_threading_id", ignoreCase = true) } ?: return
            val payloadCol = cols.firstOrNull { it.contains("payload", ignoreCase = true) } ?: return
            val tsCol = cols.firstOrNull { it.contains("timestamp", ignoreCase = true) }

            val q = db.rawQuery("SELECT $otidCol, $payloadCol ${if (tsCol != null) ", $tsCol" else ""} FROM $table", null)
            var count = 0
            while (q.moveToNext()) {
                val otid = q.getString(0)
                val text = extractTextFromPayload(q, 1)
                val ts = if (tsCol != null && !q.isNull(2)) q.getString(2) else null

                if (!otid.isNullOrBlank() && !text.isNullOrBlank()) {
                    messageCache[otid] = text
                    if (!ts.isNullOrBlank()) messageCache[ts] = text
                    count++
                    XposedBridge.log("$TAG: [Reverb] Recovered from $table: otid=$otid -> '$text'")
                }
            }
            q.close()
            if (count > 0) {
                XposedBridge.log("$TAG: [Reverb] Loaded $count deleted/persisted messages from $dbName.$table")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: [Reverb] Error scanning $table: $t")
        }
    }

    private fun extractTextFromPayload(cursor: Cursor, colIdx: Int): String? {
        try {
            val blob = try { cursor.getBlob(colIdx) } catch (_: Throwable) { null }
            val s = if (blob != null) {
                String(blob, Charsets.UTF_8)
            } else {
                cursor.getString(colIdx) ?: return null
            }

            if (s.contains("\"text\":")) {
                val m = Regex(""""text"\s*:\s*"([^"]+)"""").find(s)
                if (m != null) return m.groupValues[1]
            }
            if (s.contains("\"body\":")) {
                val m = Regex(""""body"\s*:\s*"([^"]+)"""").find(s)
                if (m != null) return m.groupValues[1]
            }

            // Find valid human-readable string sequence from payload
            val matches = Regex("""[\p{L}\p{N}\p{P}\p{Z}]{2,}""").findAll(s)
            val candidates = matches
                .map { it.value.trim() }
                .filter { it.length >= 2 && !it.startsWith("mid.") && !it.startsWith("http") && !it.contains("com.facebook") && !it.all { c -> c.isDigit() } }
                .toList()

            return candidates.maxByOrNull { it.length }
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * Intercept system notifications to capture message content before chat opens.
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
                            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()

                            val resolvedText = when {
                                !bigText.isNullOrBlank() -> bigText
                                !text.isNullOrBlank() -> text
                                else -> null
                            }

                            if (!title.isNullOrBlank() && !resolvedText.isNullOrBlank()) {
                                putNotif(title, resolvedText)
                                if (title.contains(": ")) {
                                    val individualSender = title.substringAfter(": ").trim()
                                    if (individualSender.isNotEmpty()) {
                                        putNotif(individualSender, resolvedText)
                                    }
                                }
                                XposedBridge.log("$TAG: [Notif] Cached message from '$title': '$resolvedText'")
                            }

                            val messagesArray = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
                            if (messagesArray != null) {
                                for (msgObj in messagesArray) {
                                    if (msgObj is Bundle) {
                                        val mText = msgObj.getCharSequence("text")?.toString()?.trim()
                                        val mSender = msgObj.getCharSequence("sender")?.toString()?.trim() ?: title
                                        if (!mSender.isNullOrBlank() && !mText.isNullOrBlank()) {
                                            putNotif(mSender, mText)
                                            XposedBridge.log("$TAG: [Notif] Cached bundle message from '$mSender': '$mText'")
                                        }
                                    }
                                }
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
     * Hook all message models (Text, Photo, Sticker, Video, Link).
     */
    private fun hookMessageModels(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        hookSingleModel(lp, modulePath, "text_class", "[TextMessage text=") { instance, clazz ->
            var text: String? = null
            for (field in clazz.declaredFields) {
                if (CharSequence::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val v = field.get(instance)?.toString()
                    if (!v.isNullOrBlank()) {
                        text = v
                        break
                    }
                }
            }
            text
        }

        hookSingleModel(lp, modulePath, "photo_class", "[PhotoMessage photoCount=") { _, _ ->
            "📷 [Photo]"
        }

        hookSingleModel(lp, modulePath, "sticker_class", "[StickerMessage stickerId=") { _, _ ->
            "🏷️ [Sticker]"
        }

        hookSingleModel(lp, modulePath, "video_class", "[VideoMessage videoAttachment=") { _, _ ->
            "🎥 [Video]"
        }

        hookSingleModel(lp, modulePath, "xma_class", "[XmaMessage title=") { _, _ ->
            "🔗 [Link/Attachment]"
        }
    }

    private fun hookSingleModel(
        lp: XC_LoadPackage.LoadPackageParam,
        modulePath: String,
        cacheKey: String,
        pattern: String,
        extractText: (instance: Any, clazz: Class<*>) -> String?
    ) {
        val modelClass = Resolver.resolveClass(lp, modulePath, cacheKey) { bridge ->
            val results = bridge.findClass {
                matcher {
                    usingStrings(pattern)
                }
            }
            results.firstOrNull()
        }

        if (modelClass == null) {
            XposedBridge.log("$TAG: [$cacheKey] not found via DexKit pattern: $pattern")
            return
        }

        for (constructor in modelClass.declaredConstructors) {
            XposedBridge.hookMethod(constructor, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val instance = param.thisObject ?: return
                    try {
                        val ids = extractAllIdentifiers(instance)
                        val text = extractText(instance, modelClass)
                        if (ids.isNotEmpty() && text != null) {
                            for (id in ids) {
                                putMessage(id, text)
                            }
                            XposedBridge.log("$TAG: [Cache] Saved message (ids=$ids): '$text'")
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: [$cacheKey] Error caching: $t")
                    }
                }
            })
        }
        XposedBridge.log("$TAG: [$cacheKey] Hooked ${modelClass.name} constructors")
    }

    /**
     * Hook TombstoneMessage Constructor.
     * Intercepts recalled messages, looks up original text across all identifiers,
     * and replaces snippet with the recovered message.
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
                        val ids = extractAllIdentifiers(instance)
                        for (field in tombstoneClass.declaredFields) {
                            if (field.type == String::class.java) {
                                field.isAccessible = true
                                val oldSnippet = field.get(instance) as? String ?: continue
                                if (oldSnippet.startsWith("🔴 [Recalled]")) break

                                var cachedText: String? = null
                                for (id in ids) {
                                    val t = messageCache[id]
                                    if (t != null) {
                                        cachedText = t
                                        break
                                    }
                                }

                                val sender = oldSnippet
                                    .substringBefore(" deleted")
                                    .substringBefore(" đã thu hồi")
                                    .substringBefore(" removed")
                                    .substringBefore(" recalled")
                                    .trim()

                                val notif = if (sender.isNotEmpty()) {
                                    notifCache[sender] ?: notifCache.entries.firstOrNull {
                                        it.key.contains(sender, ignoreCase = true)
                                    }?.value
                                } else null

                                val newSnippet = when {
                                    cachedText != null -> "🔴 [Recalled]: $cachedText"
                                    notif != null -> "🔴 [Recalled]: $notif"
                                    else -> "🔴 [Recalled] $oldSnippet"
                                }

                                field.set(instance, newSnippet)
                                XposedBridge.log("$TAG: [TombstoneModel] Intercepted tombstone (ids=$ids, sender='$sender'): '$oldSnippet' -> '$newSnippet'")
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
     * Recursively extracts all identifiers (id, timeSent, MIDs, strings, longs) from a model object.
     */
    private fun extractAllIdentifiers(instance: Any): Set<String> {
        val ids = mutableSetOf<String>()
        val str = instance.toString()

        val idMatch = Regex("""\bid=([^, \]]+)""").find(str)
        if (idMatch != null) ids.add(idMatch.groupValues[1])

        val timeMatch = Regex("""\btimeSent=(\d+)""").find(str)
        if (timeMatch != null) ids.add(timeMatch.groupValues[1])

        fun inspectObj(obj: Any?, depth: Int) {
            if (obj == null || depth > 2) return
            val clazz = obj.javaClass
            val cName = clazz.name
            if (cName.startsWith("java.lang.String") || cName.startsWith("android.")) return

            for (field in clazz.declaredFields) {
                try {
                    field.isAccessible = true
                    val v = field.get(obj) ?: continue
                    when (v) {
                        is String -> {
                            if (v.length in 4..64 && (v.all { it.isDigit() } || v.startsWith("mid.") || v.startsWith("mid$") || v.startsWith("m_"))) {
                                ids.add(v)
                            }
                        }
                        is Long -> {
                            if (v > 1000000000000L) { // timestamp in ms
                                ids.add(v.toString())
                            }
                        }
                        else -> {
                            if (depth < 2 && !v.javaClass.isPrimitive && !v.javaClass.isArray) {
                                inspectObj(v, depth + 1)
                            }
                        }
                    }
                } catch (_: Throwable) {}
            }
        }

        inspectObj(instance, 0)
        return ids
    }
}
