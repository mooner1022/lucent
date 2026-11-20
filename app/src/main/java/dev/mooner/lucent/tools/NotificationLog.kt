package dev.mooner.lucent.tools

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import dev.mooner.lucent.protocol.LucentJson
import kotlinx.serialization.builtins.ListSerializer

/**
 * 휴대폰에 온 알림의 기록(`notifications.recent`가 읽는다). 대화 기록과 따로 둔다(`lucent-notices.db`).
 *
 * - 알림 하나가 한 줄이다. 떠 있는 알림이 고쳐 올라오면(메신저의 새 메시지) 그 줄을 고치고, 사라진 뒤 다시 오면 새 줄이다.
 * - [NotificationLogRules.KEEP_HOURS]시간, [NotificationLogRules.MAX_ROWS]줄까지만 둔다. 알림 기록을 끄거나 연결을
 *   해제하면 모두 지운다. 글은 이 기기 안에만 있다(앱 백업은 꺼져 있다). 로그에 글을 남기지 않는다.
 * - 답장에 쓰는 알림의 단추는 프로세스 안에만 있다([NoticeReplies]).
 */
class NotificationLog(context: Context, name: String? = "lucent-notices.db") {

    private val helper = object : SQLiteOpenHelper(context.applicationContext, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE notice(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    nkey TEXT NOT NULL,
                    pkg TEXT NOT NULL,
                    app TEXT NOT NULL,
                    title TEXT NOT NULL,
                    body TEXT NOT NULL,
                    sub TEXT,
                    conversation TEXT,
                    messages TEXT,
                    category TEXT,
                    posted_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    removed_at INTEGER)""",
            )
            db.execSQL("CREATE INDEX notice_key ON notice(nkey, removed_at)")
            db.execSQL("CREATE INDEX notice_updated ON notice(updated_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    private val db: SQLiteDatabase get() = helper.writableDatabase
    private val messagesSerializer = ListSerializer(NoticeMessage.serializer())

    /** 알림이 올라왔거나 고쳐졌다. 기록한 줄의 id를 돌려준다. */
    @Synchronized
    fun posted(draft: NoticeDraft, now: Long): Long {
        val values = ContentValues().apply {
            put("pkg", draft.pkg)
            put("app", draft.app)
            put("title", draft.title)
            put("body", draft.text)
            put("sub", draft.sub)
            put("conversation", draft.conversation)
            put("messages", if (draft.messages.isEmpty()) null else LucentJson.encodeToString(messagesSerializer, draft.messages))
            put("category", draft.category)
            put("posted_at", draft.postedAt)
            put("updated_at", now)
        }
        val live = liveId(draft.key)
        val id = if (live != null) {
            db.update("notice", values, "id = ?", arrayOf(live.toString()))
            live
        } else {
            values.put("nkey", draft.key)
            db.insert("notice", null, values)
        }
        prune(now)
        return id
    }

    /** 알림이 알림 창에서 사라졌다. */
    @Synchronized
    fun removed(key: String, now: Long) {
        db.update("notice", ContentValues().apply { put("removed_at", now) }, "nkey = ? AND removed_at IS NULL", arrayOf(key))
    }

    /** 지금 떠 있는 알림의 열쇠들로 맞춘다. 기록에는 떠 있다고 되어 있지만 이제 없는 것은 사라진 것으로 둔다. */
    @Synchronized
    fun reconcile(activeKeys: Set<String>, now: Long) {
        val stale = mutableListOf<Long>()
        db.rawQuery("SELECT id, nkey FROM notice WHERE removed_at IS NULL", null).use { c ->
            while (c.moveToNext()) if (c.getString(1) !in activeKeys) stale += c.getLong(0)
        }
        val values = ContentValues().apply { put("removed_at", now) }
        for (id in stale) db.update("notice", values, "id = ?", arrayOf(id.toString()))
    }

    /** 조건에 맞는 알림을 최근 순으로 [NoticeQuery.limit]보다 하나 더(더 있는지 알려고). */
    @Synchronized
    fun recent(query: NoticeQuery, now: Long): List<Notice> {
        val out = mutableListOf<Notice>()
        db.rawQuery("SELECT * FROM notice WHERE updated_at >= ? ORDER BY updated_at DESC, id DESC", arrayOf(query.since(now).toString())).use { c ->
            while (c.moveToNext() && out.size <= query.limit) {
                val notice = read(c)
                if (query.matches(notice, now)) out += notice
            }
        }
        return out
    }

    @Synchronized
    fun byId(id: Long): Notice? =
        db.rawQuery("SELECT * FROM notice WHERE id = ?", arrayOf(id.toString())).use { c -> if (c.moveToFirst()) read(c) else null }

    @Synchronized
    fun count(): Int = db.rawQuery("SELECT COUNT(*) FROM notice", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    @Synchronized
    fun clear() {
        db.delete("notice", null, null)
    }

    private fun liveId(key: String): Long? =
        db.rawQuery("SELECT id FROM notice WHERE nkey = ? AND removed_at IS NULL ORDER BY id DESC LIMIT 1", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }

    /** 오래된 것과 상한을 넘는 것을 지운다. */
    private fun prune(now: Long) {
        db.delete("notice", "updated_at < ?", arrayOf((now - NotificationLogRules.KEEP_MS).toString()))
        db.execSQL(
            "DELETE FROM notice WHERE id NOT IN (SELECT id FROM notice ORDER BY updated_at DESC, id DESC LIMIT ${NotificationLogRules.MAX_ROWS})",
        )
    }

    private fun read(c: Cursor): Notice {
        fun str(column: String) = c.getString(c.getColumnIndexOrThrow(column))
        fun long(column: String) = c.getLong(c.getColumnIndexOrThrow(column))
        val removedIndex = c.getColumnIndexOrThrow("removed_at")
        val messages = str("messages")?.let { runCatching { LucentJson.decodeFromString(messagesSerializer, it) }.getOrNull() }.orEmpty()
        return Notice(
            id = long("id"),
            draft = NoticeDraft(
                key = str("nkey"),
                pkg = str("pkg"),
                app = str("app"),
                title = str("title"),
                text = str("body"),
                sub = str("sub"),
                conversation = str("conversation"),
                messages = messages,
                category = str("category"),
                postedAt = long("posted_at"),
            ),
            updatedAt = long("updated_at"),
            removedAt = if (c.isNull(removedIndex)) null else c.getLong(removedIndex),
        )
    }

    companion object {
        @Volatile private var instance: NotificationLog? = null

        fun get(context: Context): NotificationLog =
            instance ?: synchronized(this) { instance ?: NotificationLog(context).also { instance = it } }
    }
}
