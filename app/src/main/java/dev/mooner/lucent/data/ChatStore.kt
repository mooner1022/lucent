package dev.mooner.lucent.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction
import dev.mooner.lucent.protocol.Card
import dev.mooner.lucent.protocol.FileRef
import dev.mooner.lucent.protocol.HistoryItem
import dev.mooner.lucent.protocol.LucentJson
import dev.mooner.lucent.protocol.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.time.OffsetDateTime

/**
 * 화면용 기록(사양 12.1). SQLite 하나에 서버 프레임, 앱에서 보낸 말, 가져온 기록을 시각순으로 둔다.
 *
 * - `s:<seq>:<내용 해시>`: 번호 붙는 프레임. 같은 `ref`의 카드는 한 줄이고 `card_closed`가 그 줄을 고친다.
 *   키에 번호만 쓰지 않는 까닭: 서버가 저장소를 초기화해 번호를 처음부터 다시 매기면(`resumed.last`가 앱의 번호보다 작다)
 *   새 프레임의 번호가 예전 줄과 겹친다. 번호만으로 거르면 새 말이 조용히 버려진다. 같은 번호에 같은 내용일 때만 같은 줄로 본다.
 * - `u:<localId>`: 이 앱에서 보낸 말.
 * - `h:<id>`: `history`로 가져온 기록.
 * - 마지막으로 처리한 `seq`는 프레임과 같은 트랜잭션에 저장한다(처리와 번호가 어긋나지 않게).
 */
class ChatStore(context: Context, name: String? = "lucent-chat.db") {

    private val helper = Helper(context.applicationContext, name)
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(io)

    private val _items = MutableStateFlow<List<StoredItem>>(emptyList())
    /** 화면에 보일 가장 최근 [window]건. 오래된 것부터. */
    val items: StateFlow<List<StoredItem>> = _items

    private val _hasMoreLocal = MutableStateFlow(false)
    val hasMoreLocal: StateFlow<Boolean> = _hasMoreLocal

    private var window = PAGE
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch { refreshNow() }
        @OptIn(FlowPreview::class)
        scope.launch {
            refreshRequests.consumeAsFlow().debounce(40).collect { refreshNow() }
        }
    }

    private fun requestRefresh() { refreshRequests.trySend(Unit) }

    private fun refreshNow() {
        val db = helper.readableDatabase
        val list = db.rawQuery(
            "SELECT $COLUMNS FROM item ORDER BY sort_at DESC, id DESC LIMIT ?", arrayOf((window + 1).toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.toItem()) } }
        _hasMoreLocal.value = list.size > window
        _items.value = list.take(window).asReversed()
    }

    // ------------------------------------------------------------ 메타

    suspend fun lastSeq(): Long = meta(META_LAST_SEQ)?.toLongOrNull() ?: 0L

    suspend fun setLastSeq(seq: Long) = withContext(io) { putMeta(helper.writableDatabase, META_LAST_SEQ, seq.toString()) }

    suspend fun meta(key: String): String? = withContext(io) {
        helper.readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    suspend fun putMeta(key: String, value: String) = withContext(io) { putMeta(helper.writableDatabase, key, value) }

    private fun putMeta(db: SQLiteDatabase, key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO meta(key, value) VALUES (?, ?)", arrayOf(key, value))
    }

    suspend fun isEmpty(): Boolean = withContext(io) {
        helper.readableDatabase.rawQuery("SELECT 1 FROM item LIMIT 1", null).use { !it.moveToFirst() }
    }

    // ------------------------------------------------------------ 서버 프레임

    /**
     * 번호 붙는 프레임 하나를 저장하고 `lastSeq`를 [ServerFrame.Numbered.seq]로 올린다.
     * 이미 저장한 번호는 건드리지 않는다.
     */
    suspend fun applyFrame(frame: ServerFrame.Numbered, now: Long) = withContext(io) {
        val db = helper.writableDatabase
        db.transaction {
            val key = frameKey(frame)
            when (frame) {
                is ServerFrame.Text -> insertFrame(db, key, now, Kind.TEXT, frame.body, null, null, emptyList(), frame.seq)
                // 스티커만 있는 줄: 글은 비우고 스티커를 붙인다. 묶음·시각·출처는 글과 똑같이 다뤄진다.
                is ServerFrame.Sticker -> insertFrame(
                    db, key, now, Kind.TEXT, "", null, null, emptyList(), frame.seq,
                    sticker = StickerRef(frame.id.ifEmpty { null }, frame.name),
                )
                is ServerFrame.Note -> insertFrame(db, key, now, Kind.NOTE, frame.body, null, null, emptyList(), frame.seq)
                is ServerFrame.Files -> insertFrame(db, key, now, Kind.FILES, frame.body, null, null, frame.files, frame.seq)
                is ServerFrame.CardOpen -> {
                    // 같은 ref가 이미 있으면(다시 받기가 겹친 경우) 내용만 맞춘다.
                    if (!updateCard(db, frame.ref, frame.card, onlyIfPending = true))
                        insertFrame(db, key, now, Kind.CARD, frame.card.headline, frame.ref, frame.card, emptyList(), frame.seq)
                }
                is ServerFrame.CardClosed -> {
                    // 닫힌 카드가 이 앱에 남아 있지 않으면(보관 기간 밖 등) 닫힌 채로 새로 보인다.
                    if (!updateCard(db, frame.ref, frame.card, onlyIfPending = false))
                        insertFrame(db, key, now, Kind.CARD, frame.card.headline, frame.ref, frame.card, emptyList(), frame.seq)
                }
            }
            val last = meta(db, META_LAST_SEQ)?.toLongOrNull() ?: 0L
            if (frame.seq > last) putMeta(db, META_LAST_SEQ, frame.seq.toString())
        }
        requestRefresh()
    }

    private fun frameKey(frame: ServerFrame.Numbered): String {
        val content = when (frame) {
            is ServerFrame.Text -> "t" + frame.body
            is ServerFrame.Note -> "n" + frame.body
            is ServerFrame.Files -> "f" + frame.body + frame.files.joinToString { it.id }
            is ServerFrame.CardOpen -> "c" + frame.ref
            is ServerFrame.CardClosed -> "x" + frame.ref
            is ServerFrame.Sticker -> "k" + frame.id + frame.name
        }
        return "s:${frame.seq}:${content.hashCode()}"
    }

    private fun insertFrame(
        db: SQLiteDatabase, key: String, now: Long, kind: Kind, body: String,
        ref: String?, card: Card?, files: List<FileRef>, seq: Long, sticker: StickerRef? = null,
    ) {
        val values = ContentValues().apply {
            put("key", key)
            // 받은 때. 같은 시각의 프레임은 id 순으로 정렬되도록 이전 줄보다 앞서지 않게 한다.
            put("sort_at", maxOf(now, newestSortAt(db)))
            put("sender", Sender.LUCIA.name)
            put("kind", kind.name)
            put("body", body)
            put("ref", ref)
            put("card", card?.let { LucentJson.encodeToString(Card.serializer(), it) })
            put("files", encodeFiles(files))
            put("sticker", encodeSticker(sticker))
            put("seq", seq)
            put("delivery", Delivery.SENT.name)
        }
        db.insertWithOnConflict("item", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    private fun updateCard(db: SQLiteDatabase, ref: String, card: Card, onlyIfPending: Boolean): Boolean {
        val existing = db.rawQuery("SELECT id, card FROM item WHERE ref=? LIMIT 1", arrayOf(ref)).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getString(1) else null
        } ?: return false
        if (onlyIfPending) {
            // 이미 닫힌 카드를 열린 카드로 되돌리지 않는다.
            val old = existing.second?.let { runCatching { LucentJson.decodeFromString(Card.serializer(), it) }.getOrNull() }
            if (old != null && !old.isPending) return true
        }
        val values = ContentValues().apply {
            put("card", LucentJson.encodeToString(Card.serializer(), card))
            put("body", card.headline)
        }
        db.update("item", values, "id=?", arrayOf(existing.first.toString()))
        return true
    }

    // ------------------------------------------------------------ 이 앱에서 보낸 말

    suspend fun insertUser(localId: String, text: String, files: List<FileRef>, now: Long, sticker: StickerRef? = null) = withContext(io) {
        val db = helper.writableDatabase
        val values = ContentValues().apply {
            put("key", "u:$localId")
            put("sort_at", maxOf(now, newestSortAt(db)))
            put("sender", Sender.USER.name)
            put("kind", if (files.isEmpty()) Kind.TEXT.name else Kind.FILES.name)
            put("body", text)
            put("files", encodeFiles(files))
            put("sticker", encodeSticker(sticker))
            put("delivery", Delivery.SENDING.name)
            put("local_id", localId)
        }
        db.insertWithOnConflict("item", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        requestRefresh()
    }

    suspend fun setDelivery(localId: String, delivery: Delivery) = withContext(io) {
        helper.writableDatabase.update(
            "item", ContentValues().apply { put("delivery", delivery.name) }, "local_id=?", arrayOf(localId),
        )
        requestRefresh()
    }

    /** 연결이 끊겼을 때 `accepted`를 못 받은 말을 "확인 안 됨"으로 바꾼다. */
    suspend fun markSendingUnconfirmed() = withContext(io) {
        val n = helper.writableDatabase.update(
            "item", ContentValues().apply { put("delivery", Delivery.UNCONFIRMED.name) },
            "delivery=?", arrayOf(Delivery.SENDING.name),
        )
        if (n > 0) requestRefresh()
    }

    /** 서버가 `empty`·`too_long`·`bad_file`·`bad_sticker`로 거절했을 때, 가장 최근에 보낸 말을 실패로 표시한다. */
    suspend fun markLatestSendingFailed() = withContext(io) {
        helper.writableDatabase.execSQL(
            "UPDATE item SET delivery=? WHERE id=(SELECT id FROM item WHERE delivery=? ORDER BY sort_at DESC, id DESC LIMIT 1)",
            arrayOf(Delivery.FAILED.name, Delivery.SENDING.name),
        )
        requestRefresh()
    }

    suspend fun deleteLocal(localId: String) = withContext(io) {
        helper.writableDatabase.delete("item", "local_id=?", arrayOf(localId))
        requestRefresh()
    }

    // ------------------------------------------------------------ 가져온 기록

    /**
     * `history` 결과를 저장한다. 이미 있는 줄과 겹치는 것(같은 말·같은 글, 5분 안)은 건너뛰고, [after]가 있으면 그
     * 시각보다 나중의 기록만 받는다. 새로 들어간 건수를 돌려준다.
     *
     * 기록의 스티커는 글 속의 한 줄(`[스티커:이름]`)이다(app-expressions.md 6장). 실시간으로는 글(`text`)과 스티커(`sticker`)가
     * 따로 왔으므로, 여기서도 글과 스티커로 나눠 저장하고 겹침도 나눈 모양으로 본다. 나누지 않으면 스티커가 낀 말은
     * 기록을 다시 받을 때마다 한 번 더 보인다.
     */
    suspend fun importHistory(items: List<HistoryItem>, after: Long? = null): Int = withContext(io) {
        val db = helper.writableDatabase
        var added = 0
        db.transaction {
            for (item in items) {
                val at = parseInstant(item.at)
                if (after != null && at <= after) continue
                val sender = Sender.fromHistory(item.from)
                val split = StickerLine.split(item.body)
                if (existsSimilar(db, sender, split.text, split.sticker, at)) continue
                val values = ContentValues().apply {
                    put("key", "h:${item.id}")
                    put("sort_at", at)
                    put("sender", sender.name)
                    put("kind", Kind.TEXT.name)
                    put("body", split.text)
                    put("sticker", encodeSticker(split.sticker))
                    put("delivery", Delivery.SENT.name)
                    put("conversation", item.conversation)
                }
                if (db.insertWithOnConflict("item", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
        }
        if (added > 0) requestRefresh()
        added
    }

    /**
     * 같은 쪽이 가까운 때에 한 같은 말이 이미 있는가. 글이 있으면 글로 본다(스티커는 실시간에서 다른 줄로 왔을 수 있다).
     * 글 없이 스티커뿐이면 같은 이름의 스티커 줄이 있는지 본다.
     */
    private fun existsSimilar(db: SQLiteDatabase, sender: Sender, text: String, sticker: StickerRef?, at: Long): Boolean {
        if (text.isNotEmpty() || sticker == null) {
            val same = db.rawQuery(
                "SELECT 1 FROM item WHERE sender=? AND body=? AND ABS(sort_at-?) < ? LIMIT 1",
                arrayOf(sender.name, text, at.toString(), SIMILAR_WITHIN_MS.toString()),
            ).use { it.moveToFirst() }
            return same || existsInParts(db, sender, text, at)
        }
        val want = Expressions.nameKey(sticker.name)
        return db.rawQuery(
            "SELECT sticker FROM item WHERE sender=? AND body='' AND sticker IS NOT NULL AND ABS(sort_at-?) < ?",
            arrayOf(sender.name, at.toString(), SIMILAR_WITHIN_MS.toString()),
        ).use { c ->
            var found = false
            while (!found && c.moveToNext()) found = decodeSticker(c.getString(0))?.let { Expressions.nameKey(it.name) } == want
            found
        }
    }

    /**
     * 긴 답은 실시간으로는 여러 `text` 프레임으로 나뉘어 오고, 기록에는 빈 줄로 이어 붙인 한 덩어리로 남는다.
     * 가까운 때에 잇달아 온 줄들을 이어 붙인 것이 이 기록과 같으면 이미 있는 말이다.
     */
    private fun existsInParts(db: SQLiteDatabase, sender: Sender, text: String, at: Long): Boolean {
        if (HISTORY_PART_GAP !in text) return false
        val bodies = db.rawQuery(
            "SELECT body FROM item WHERE sender=? AND kind IN (?, ?) AND body != '' AND ABS(sort_at-?) < ? ORDER BY sort_at, id",
            arrayOf(sender.name, Kind.TEXT.name, Kind.FILES.name, at.toString(), SIMILAR_WITHIN_MS.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        for (start in bodies.indices) {
            if (!text.startsWith(bodies[start])) continue
            val joined = StringBuilder(bodies[start])
            for (next in start + 1 until bodies.size) {
                joined.append(HISTORY_PART_GAP).append(bodies[next])
                if (joined.length > text.length) break
                if (joined.length == text.length && joined.contentEquals(text)) return true
            }
        }
        return false
    }

    suspend fun newestAt(): Long? = withContext(io) {
        helper.readableDatabase.rawQuery("SELECT MAX(sort_at) FROM item", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }

    // ------------------------------------------------------------ 창

    /** 화면에 보이는 줄을 더 늘린다. 저장된 것이 더 없으면 false. */
    suspend fun showMore(): Boolean = withContext(io) {
        if (!_hasMoreLocal.value) return@withContext false
        window += PAGE
        refreshNow()
        true
    }

    suspend fun clear() = withContext(io) {
        helper.writableDatabase.transaction {
            execSQL("DELETE FROM item")
            execSQL("DELETE FROM meta")
        }
        window = PAGE
        refreshNow()
    }

    // ------------------------------------------------------------ 내부

    private fun newestSortAt(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT MAX(sort_at) FROM item", null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L }

    private fun meta(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun encodeFiles(files: List<FileRef>): String? =
        if (files.isEmpty()) null else LucentJson.encodeToString(ListSerializer(FileRef.serializer()), files)

    private fun encodeSticker(sticker: StickerRef?): String? =
        sticker?.let { LucentJson.encodeToString(StickerRef.serializer(), it) }

    private fun decodeSticker(text: String?): StickerRef? =
        text?.let { runCatching { LucentJson.decodeFromString(StickerRef.serializer(), it) }.getOrNull() }?.takeIf { it.name.isNotEmpty() || it.id != null }

    private fun Cursor.toItem(): StoredItem {
        fun s(name: String) = getString(getColumnIndexOrThrow(name))
        fun sn(name: String): String? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
        return StoredItem(
            id = getLong(getColumnIndexOrThrow("id")),
            key = s("key"),
            at = getLong(getColumnIndexOrThrow("sort_at")),
            sender = Sender.valueOf(s("sender")),
            kind = Kind.valueOf(s("kind")),
            body = s("body"),
            ref = sn("ref"),
            card = sn("card")?.let { runCatching { LucentJson.decodeFromString(Card.serializer(), it) }.getOrNull() },
            files = sn("files")?.let { runCatching { LucentJson.decodeFromString(ListSerializer(FileRef.serializer()), it) }.getOrNull() }.orEmpty(),
            seq = getLong(getColumnIndexOrThrow("seq")),
            delivery = Delivery.valueOf(s("delivery")),
            localId = sn("local_id"),
            conversation = sn("conversation"),
            sticker = decodeSticker(sn("sticker")),
        )
    }

    /** 판 2: 스티커 칸(`sticker`)이 더해졌다. */
    private class Helper(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE item(
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    key TEXT NOT NULL UNIQUE,
                    sort_at INTEGER NOT NULL,
                    sender TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    body TEXT NOT NULL DEFAULT '',
                    ref TEXT,
                    card TEXT,
                    files TEXT,
                    seq INTEGER NOT NULL DEFAULT 0,
                    delivery TEXT NOT NULL DEFAULT 'SENT',
                    local_id TEXT,
                    conversation TEXT,
                    sticker TEXT
                )""",
            )
            db.execSQL("CREATE INDEX item_order ON item(sort_at, id)")
            db.execSQL("CREATE INDEX item_ref ON item(ref)")
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("ALTER TABLE item ADD COLUMN sticker TEXT")
        }
    }

    companion object {
        const val PAGE = 120
        const val META_LAST_SEQ = "last_seq"
        const val META_CLIENT = "client"
        const val META_HISTORY_OLDEST = "history_oldest_id"
        const val META_HISTORY_MORE = "history_more"
        const val META_INITIAL_IMPORT = "initial_import_done"
        private const val SIMILAR_WITHIN_MS = 5 * 60_000L
        /** 데몬이 기록에서 한 답의 조각들을 잇는 글(빈 줄 하나). */
        private const val HISTORY_PART_GAP = "\n\n"
        private const val COLUMNS = "id, key, sort_at, sender, kind, body, ref, card, files, seq, delivery, local_id, conversation, sticker"

        fun parseInstant(text: String): Long =
            runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis())
    }
}
