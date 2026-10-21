package dhp.tpl.norecall

import android.app.Notification
import android.app.NotificationManager
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
 * Messenger Anti-Unsend / Anti-Recall Hook with Persistent Disk Cache
 * and Local SQLite Database Scanner.
 */
object MessengerHook {

    const val TARGET = "com.facebook.orca"
    private const val TAG = Resolver.TAG

    // Persistent cache file on disk
    private var cacheFile: File? = null

    // Message ID -> text (e.g. "410801359" -> "Hello")
    private val messageCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(2000, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 2000
        }
    )

    // Sender name -> latest text (e.g. "Hồng Phúc" -> "Hello")
    private val notifCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(200, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > 200
        }
    )

    // Matches both numeric IDs (410801359) and MIDs (mid.$gAB...)
    private val idRegex = Regex("""\bid=([^, \]]+)""")

    fun install(lp: XC_LoadPackage.LoadPackageParam, modulePath: String) {
        XposedBridge.log("$TAG: Installing anti-unsend hooks for ${lp.packageName} (process: ${lp.processName})...")

        initPersistentCache(lp)
        scanLocalDatabases(lp)

        hookNotifications()
        hookMessageModels(lp, modulePath)
        hookTombstoneModel(lp, modulePath)
        hookTamUnsend(lp, modulePath)
        hookRecallDelta(lp, modulePath)
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
                XposedBridge.log("$TAG: [DiskCache] Loaded ${messageCache.size} messages, ${notifCache.size} sender entries from disk")
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
     * Scan Messenger local SQLite databases (threads_db2, msys_database, etc.)
     * to pre-load any messages currently stored on disk.
     */
    private fun scanLocalDatabases(lp: XC_LoadPackage.LoadPackageParam) {
        try {
            val dbDir = File(lp.appInfo.dataDir, "databases")
            if (!dbDir.exists()) return
            val files = dbDir.listFiles() ?: return

            for (file in files) {
                if (!file.isFile) continue
                val name = file.name
                if (name.endsWith("-wal") || name.endsWith("-journal") || name.endsWith("-shm")) continue
                if (!name.contains("threads") && !name.contains("msys") && !name.endsWith(".db")) continue

                try {
                    val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    val cursor = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null)
                    val tables = mutableListOf<String>()
                    while (cursor.moveToNext()) {
                        tables.add(cursor.getString(0))
                    }
                    cursor.close()

                    for (table in tables) {
                        if (!table.contains("message", ignoreCase = true)) continue

                        val colCursor = db.rawQuery("PRAGMA table_info($table)", null)
                        val cols = mutableListOf<String>()
                        while (colCursor.moveToNext()) {
                            cols.add(colCursor.getString(1))
                        }
                        colCursor.close()

                        val idCol = cols.firstOrNull {
                            it.equals("message_id", ignoreCase = true) ||
                            it.equals("mid", ignoreCase = true) ||
                            it.equals("id", ignoreCase = true)
                        }
                        val textCol = cols.firstOrNull {
                            it.equals("text", ignoreCase = true) ||
                            it.equals("body", ignoreCase = true) ||
                            it.equals("snippet", ignoreCase = true)
                        }

                        if (idCol != null && textCol != null) {
                            var count = 0
                            val q = db.rawQuery("SELECT $idCol, $textCol FROM $table WHERE $textCol IS NOT NULL", null)
                            while (q.moveToNext()) {
                                val id = q.getString(0)
                                val text = q.getString(1)
                                if (!id.isNullOrBlank() && !text.isNullOrBlank() &&
                                    !text.contains("deleted a message", ignoreCase = true) &&
                                    !text.contains("đã thu hồi", ignoreCase = true)) {
                                    messageCache[id] = text
                                    count++
                                }
                            }
                            q.close()
                            if (count > 0) {
                                XposedBridge.log("$TAG: [DB] Pre-loaded $count messages from ${file.name}.$table ($idCol, $textCol)")
                            }
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
     * Intercept system notifications posted by Messenger to capture message content
     * including full text from MessagingStyle and BigText.
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
                                XposedBridge.log("$TAG: [Notif] Cached message from '$title': '$resolvedText'")
                            }

                            // Extract messages array if MessagingStyle is used
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
     * Hook all message models (Text, Photo, Sticker, Video, Link) to cache their content.
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
                        val id = extractMessageId(instance)
                        val text = extractText(instance, modelClass)
                        if (id != null && text != null) {
                            putMessage(id, text)
                            XposedBridge.log("$TAG: [Cache] Saved message (id=$id): '$text'")
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
     * Intercepts deleted/recalled message rendering and replaces snippet with original text.
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
                                val sender = oldSnippet.substringBefore(" deleted")
                                    .substringBefore(" đã thu hồi")
                                    .trim()
                                val notif = if (sender.isNotEmpty()) notifCache[sender] else null

                                val newSnippet = when {
                                    cachedText != null -> "🔴 [Recalled]: $cachedText"
                                    notif != null -> "🔴 [Recalled]: $notif"
                                    else -> "🔴 [Recalled] $oldSnippet"
                                }

                                field.set(instance, newSnippet)
                                XposedBridge.log("$TAG: [TombstoneModel] Intercepted tombstone (id=$id, sender='$sender'): '$oldSnippet' -> '$newSnippet'")
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
     * Modern Messenger E2EE (TAM / Armadillo) Mailbox unsend command.
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
     * Minos / GraphQL Recall Delta Applicator.
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
