package dev.mooner.lucent.data

import androidx.test.core.app.ApplicationProvider
import dev.mooner.lucent.protocol.Card
import dev.mooner.lucent.protocol.CardButton
import dev.mooner.lucent.protocol.FileRef
import dev.mooner.lucent.protocol.HistoryItem
import dev.mooner.lucent.protocol.ServerFrame
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatStoreTest {

    private lateinit var store: ChatStore
    private val t0 = 1_800_000_000_000L

    @Before fun setUp() {
        store = ChatStore(ApplicationProvider.getApplicationContext(), name = null)
    }

    @After fun tearDown() = runBlocking { store.clear() }

    private fun items(size: Int) = runBlocking { withTimeout(5_000) { store.items.first { it.size == size } } }

    private val pending = Card(headline = "확인해 주세요", title = "메일 보내기", tone = "pending",
        buttons = listOf(CardButton("approve:a", "보내기", primary = true), CardButton("reject:a", "거절")))
    private val closed = Card(headline = "✅ 보냈습니다", tone = "success")

    @Test fun `프레임을 번호와 함께 저장하고 lastSeq를 올린다`() = runBlocking {
        store.applyFrame(ServerFrame.Text(1, false, "안녕하세요"), t0)
        store.applyFrame(ServerFrame.Note(2, false, "찾는 중"), t0 + 1)
        store.applyFrame(ServerFrame.Files(3, false, "정리했어요", listOf(FileRef("f_1", "정리.md", 10, "text/markdown", "text"))), t0 + 2)
        val rows = items(3)
        assertEquals(listOf(Kind.TEXT, Kind.NOTE, Kind.FILES), rows.map { it.kind })
        assertEquals("f_1", rows[2].files.single().id)
        assertEquals(3L, store.lastSeq())
    }

    @Test fun `같은 번호를 다시 넣어도 한 줄이다`() = runBlocking {
        store.applyFrame(ServerFrame.Text(5, false, "한 번"), t0)
        store.applyFrame(ServerFrame.Text(5, true, "한 번"), t0 + 1)
        assertEquals(1, items(1).size)
    }

    @Test fun `서버가 번호를 처음부터 다시 매겨도 새 프레임을 버리지 않는다`() = runBlocking {
        store.applyFrame(ServerFrame.Text(1, false, "예전 첫 말"), t0)
        store.applyFrame(ServerFrame.Text(2, false, "예전 둘째 말"), t0 + 1)
        store.setLastSeq(0) // resumed.last < lastSeq 로 허브가 번호를 맞춘 뒤
        store.applyFrame(ServerFrame.Text(1, false, "새 첫 말"), t0 + 2)
        val rows = items(3)
        assertEquals(listOf("예전 첫 말", "예전 둘째 말", "새 첫 말"), rows.map { it.body })
    }

    @Test fun `card_closed는 같은 ref의 줄을 닫힌 카드로 바꾼다`() = runBlocking {
        store.applyFrame(ServerFrame.CardOpen(1, false, "ref-1", pending), t0)
        assertTrue(items(1).single().card!!.isPending)
        store.applyFrame(ServerFrame.CardClosed(2, false, "ref-1", closed), t0 + 1)
        val rows = store.items.first { it.single().card?.isPending == false }
        assertEquals(1, rows.size)
        assertEquals("✅ 보냈습니다", rows.single().card!!.headline)
        assertEquals(2L, store.lastSeq())
    }

    @Test fun `닫힌 카드를 열린 카드로 되돌리지 않는다`() = runBlocking {
        store.applyFrame(ServerFrame.CardOpen(1, false, "ref-1", pending), t0)
        store.applyFrame(ServerFrame.CardClosed(2, false, "ref-1", closed), t0 + 1)
        store.applyFrame(ServerFrame.CardOpen(3, true, "ref-1", pending), t0 + 2) // 겹친 다시 받기
        val rows = store.items.first { it.size == 1 && it.single().card?.isPending == false }
        assertFalse(rows.single().card!!.isPending)
    }

    @Test fun `열린 줄이 없는 card_closed는 닫힌 채로 새로 보인다`() = runBlocking {
        store.applyFrame(ServerFrame.CardClosed(9, false, "ref-x", closed), t0)
        assertEquals("✅ 보냈습니다", items(1).single().card!!.headline)
    }

    @Test fun `보낸 말의 상태가 바뀐다`() = runBlocking {
        store.insertUser("m-1", "내일 일정 알려 줘", emptyList(), t0)
        assertEquals(Delivery.SENDING, items(1).single().delivery)
        store.setDelivery("m-1", Delivery.SENT)
        assertEquals(Delivery.SENT, store.items.first { it.single().delivery == Delivery.SENT }.single().delivery)
    }

    @Test fun `연결이 끊기면 받았는지 모르는 말은 확인 안 됨이 된다`() = runBlocking {
        store.insertUser("m-1", "하나", emptyList(), t0)
        store.insertUser("m-2", "둘", emptyList(), t0 + 1)
        store.setDelivery("m-1", Delivery.SENT)
        store.markSendingUnconfirmed()
        val rows = store.items.first { list -> list.size == 2 && list.any { it.delivery == Delivery.UNCONFIRMED } }
        assertEquals(Delivery.SENT, rows.first { it.localId == "m-1" }.delivery)
        assertEquals(Delivery.UNCONFIRMED, rows.first { it.localId == "m-2" }.delivery)
    }

    @Test fun `서버가 거절하면 가장 최근에 보내는 중이던 말이 실패가 된다`() = runBlocking {
        store.insertUser("m-1", "하나", emptyList(), t0)
        store.insertUser("m-2", "둘", emptyList(), t0 + 1)
        store.markLatestSendingFailed()
        val rows = store.items.first { list -> list.any { it.delivery == Delivery.FAILED } }
        assertEquals(Delivery.FAILED, rows.first { it.localId == "m-2" }.delivery)
        assertEquals(Delivery.SENDING, rows.first { it.localId == "m-1" }.delivery)
    }

    @Test fun `지운 말은 사라진다`() = runBlocking {
        store.insertUser("m-1", "하나", emptyList(), t0)
        items(1)
        store.deleteLocal("m-1")
        assertEquals(0, items(0).size)
    }

    private fun history(id: Long, at: String, from: String, body: String, conv: String? = "app:cl-1") =
        HistoryItem(id, at, from, conv, body)

    @Test fun `기록을 시각순으로 가져온다`() = runBlocking {
        val added = store.importHistory(listOf(
            history(2, "2026-11-19T00:10:09+00:00", "lucia", "내일은…"),
            history(1, "2026-11-19T00:10:00+00:00", "user", "내일 일정 알려 줘"),
        ))
        assertEquals(2, added)
        val rows = items(2)
        assertEquals(listOf("내일 일정 알려 줘", "내일은…"), rows.map { it.body })
        assertEquals(listOf(Sender.USER, Sender.LUCIA), rows.map { it.sender })
    }

    @Test fun `이미 있는 말과 겹치는 기록은 건너뛴다`() = runBlocking {
        val now = ChatStore.parseInstant("2026-11-19T00:10:00+00:00")
        store.insertUser("m-1", "내일 일정 알려 줘", emptyList(), now)
        val added = store.importHistory(listOf(history(1, "2026-11-19T00:10:03+00:00", "user", "내일 일정 알려 줘")))
        assertEquals(0, added)
        assertEquals(1, items(1).size)
    }

    // ------------------------------------------------------------------ 스티커(docs/app-expressions.md 5, 6장)

    @Test fun `sticker 프레임은 글 없는 줄로 저장되고 번호를 올린다`() = runBlocking {
        store.applyFrame(ServerFrame.Text(70, false, "정말 축하해요!"), t0)
        store.applyFrame(ServerFrame.Sticker(71, false, "s_7a1c0e9b3f52", "축하"), t0 + 1)
        store.applyFrame(ServerFrame.Sticker(71, true, "s_7a1c0e9b3f52", "축하"), t0 + 2)
        val rows = items(2)
        assertEquals(listOf("정말 축하해요!", ""), rows.map { it.body })
        assertEquals(StickerRef("s_7a1c0e9b3f52", "축하"), rows[1].sticker)
        assertEquals(Sender.LUCIA, rows[1].sender)
        assertEquals(71L, store.lastSeq())
    }

    @Test fun `기록의 스티커 줄은 글과 스티커로 나눠 한 줄에 저장한다`() = runBlocking {
        store.importHistory(listOf(
            history(1, "2026-11-19T00:10:00+00:00", "user", "나 합격했어 :lucia_smile:\n[스티커:축하 — 꽃가루가 날리는 축하]"),
            history(2, "2026-11-19T00:10:09+00:00", "lucia", "정말 축하해요!\n[스티커:꾸벅]"),
            history(3, "2026-11-19T00:10:20+00:00", "lucia", "[스티커:꾸벅]"),
        ))
        val rows = items(3)
        assertEquals(listOf("나 합격했어 :lucia_smile:", "정말 축하해요!", ""), rows.map { it.body })
        assertEquals(listOf("축하", "꾸벅", "꾸벅"), rows.map { it.sticker?.name })
        // 기록에는 ID가 없다. 그릴 때 이름으로 목록에서 찾는다.
        assertTrue(rows.all { it.sticker?.id == null })
    }

    @Test fun `실시간으로 받은 글과 스티커는 기록을 다시 받아도 한 번만 보인다`() = runBlocking {
        val now = ChatStore.parseInstant("2026-11-19T00:10:00+00:00")
        // 내가 보낸 스티커, 루시아의 글과 바로 뒤의 스티커.
        store.insertUser("m-1", "", emptyList(), now, StickerRef("s_1", "축하"))
        store.applyFrame(ServerFrame.Text(70, false, "정말 축하해요!"), now + 2_000)
        store.applyFrame(ServerFrame.Sticker(71, false, "s_2", "꾸벅"), now + 2_500)
        items(3)
        val added = store.importHistory(listOf(
            history(1, "2026-11-19T00:10:00+00:00", "user", "[스티커:축하 — 꽃가루가 날리는 축하]"),
            history(2, "2026-11-19T00:10:03+00:00", "lucia", "정말 축하해요!\n[스티커:꾸벅]"),
        ))
        assertEquals(0, added)
        assertEquals(3, items(3).size)
        // 다른 스티커만 보낸 말은 겹치는 것이 아니다.
        assertEquals(1, store.importHistory(listOf(history(3, "2026-11-19T00:10:30+00:00", "user", "[스티커:잘자]"))))
    }

    @Test fun `데몬의 시험과 같은 글 - 실시간의 글과 기록의 글이 스티커 줄만 다르다`() = runBlocking {
        // engine/tests.rs: 보낸 글은 "합격 축하해요 :lucia_smile:", 기록은 그 글에 마지막 줄 `[스티커:축하]`가 붙는다.
        // gateway/tests.rs: 글과 스티커를 함께 보낸 말의 기록은 "나 합격했어 :lucia_smile:\n[스티커:축하 — 꽃가루]".
        val now = ChatStore.parseInstant("2026-11-16T00:09:00+00:00")
        store.insertUser("m-1", "나 합격했어 :lucia_smile:", emptyList(), now, StickerRef("s_1", "축하"))
        store.applyFrame(ServerFrame.Text(1, false, "합격 축하해요 :lucia_smile:"), now + 3_000)
        store.applyFrame(ServerFrame.Sticker(2, false, "s_1", "축하"), now + 3_200)
        items(3)
        val added = store.importHistory(listOf(
            history(1, "2026-11-16T00:09:00+00:00", "user", "나 합격했어 :lucia_smile:\n[스티커:축하 — 꽃가루]"),
            history(2, "2026-11-16T00:09:03+00:00", "lucia", "합격 축하해요 :lucia_smile:\n[스티커:축하]"),
        ))
        assertEquals(0, added)
    }

    @Test fun `여러 조각으로 온 답은 기록의 한 덩어리와 겹친다`() = runBlocking {
        val now = ChatStore.parseInstant("2026-11-19T00:10:00+00:00")
        store.applyFrame(ServerFrame.Text(1, false, "먼저 일정부터 볼게요."), now)
        store.applyFrame(ServerFrame.Text(2, false, "내일은 치과 예약만 있어요.\n\n서류는 오늘 끝내 두세요."), now + 2_000)
        store.applyFrame(ServerFrame.Sticker(3, false, "s_2", "꾸벅"), now + 2_300)
        items(3)
        // 기록은 조각을 빈 줄로 이어 붙이고, 보낸 스티커를 마지막 줄에 남긴다.
        assertEquals(0, store.importHistory(listOf(history(
            1, "2026-11-19T00:10:02+00:00", "lucia",
            "먼저 일정부터 볼게요.\n\n내일은 치과 예약만 있어요.\n\n서류는 오늘 끝내 두세요.\n[스티커:꾸벅]",
        ))))
        // 이어 붙여도 같지 않은 글은 새 말이다.
        assertEquals(1, store.importHistory(listOf(history(
            2, "2026-11-19T00:10:30+00:00", "lucia", "먼저 일정부터 볼게요.\n\n다른 이야기예요.",
        ))))
    }

    @Test fun `스티커와 함께 보낸 내 말은 한 줄이다`() = runBlocking {
        store.insertUser("m-1", "나 합격했어!", emptyList(), t0, StickerRef("s_1", "축하"))
        val row = items(1).single()
        assertEquals("나 합격했어!", row.body)
        assertEquals("s_1", row.sticker?.id)
        assertEquals(Delivery.SENDING, row.delivery)
    }

    @Test fun `after보다 오래된 기록은 가져오지 않는다`() = runBlocking {
        val cut = ChatStore.parseInstant("2026-11-19T00:10:00+00:00")
        val added = store.importHistory(listOf(
            history(1, "2026-11-19T00:09:00+00:00", "lucia", "옛 말"),
            history(2, "2026-11-19T00:11:00+00:00", "lucia", "새 말"),
        ), after = cut)
        assertEquals(1, added)
        assertEquals("새 말", items(1).single().body)
    }

    @Test fun `다른 대화에서 온 기록은 출처를 남긴다`() = runBlocking {
        store.importHistory(listOf(history(1, "2026-11-19T00:10:00+00:00", "daemon", "좋은 아침이에요", conv = null)))
        assertEquals(Sender.DAEMON, items(1).single().sender)
    }

    @Test fun `창을 늘리면 더 오래된 줄이 보인다`() = runBlocking {
        val many = (1..(ChatStore.PAGE + 30)).map { history(it.toLong(), "2026-11-19T00:%02d:%02d+00:00".format(it / 60, it % 60), "lucia", "글 $it") }
        store.importHistory(many)
        assertEquals(ChatStore.PAGE, items(ChatStore.PAGE).size)
        assertTrue(store.hasMoreLocal.first { it })
        assertTrue(store.showMore())
        assertEquals(ChatStore.PAGE + 30, items(ChatStore.PAGE + 30).size)
        assertFalse(store.showMore())
    }

    @Test fun `메타와 clear`() = runBlocking {
        store.putMeta("client", "cl-1")
        store.setLastSeq(12)
        assertEquals("cl-1", store.meta("client"))
        assertEquals(12L, store.lastSeq())
        store.applyFrame(ServerFrame.Text(13, false, "글"), t0)
        items(1)
        store.clear()
        assertEquals(0, items(0).size)
        assertEquals(0L, store.lastSeq())
        assertTrue(store.isEmpty())
    }

    // ------------------------------------------------------------------ 한 대화(결정 0066·0067)

    private val doc = FileRef("f_9c1e0a7b2d4f", "원문.md", 1834, "text/markdown", "text")

    @Test fun `sent는 다른 기기의 이름과 고른 글 표지를 지닌 내 말로 저장된다`() = runBlocking {
        store.applyFrame(
            ServerFrame.Sent(
                63, false, "이거 번역해 줘", conversation = "app:cl-desk", fromName = "데스크톱 앱",
                at = "2026-11-22T08:12:03.512300+00:00", files = listOf(doc), stickerId = "s_1", stickerName = "축하",
                contextNote = "[고른 글 120자 함께 보냄 · msedge.exe]",
            ),
            t0,
        )
        val row = items(1).single()
        assertEquals(Sender.USER, row.sender)
        assertEquals(Kind.FILES, row.kind)
        assertEquals("이거 번역해 줘", row.body)
        assertEquals("데스크톱 앱", row.device)
        assertEquals("app:cl-desk", row.conversation)
        assertEquals("[고른 글 120자 함께 보냄 · msedge.exe]", row.contextNote)
        assertEquals(StickerRef("s_1", "축하"), row.sticker)
        assertEquals(63L, store.lastSeq())
        // 내 말은 울리지 않는다: 울렸을 수 있는 번호에 들지 않는다.
        assertEquals(null, store.meta(ChatStore.META_LAST_ALERT_SEQ))
    }

    @Test fun `다른 기기 대화의 답은 그 대화를 지닌다`() = runBlocking {
        store.applyFrame(ServerFrame.Text(92, false, "정리했어요", alert = false, conversation = "app:cl-desk"), t0)
        val row = items(1).single()
        assertEquals("app:cl-desk", row.conversation)
        assertEquals("92", store.meta(ChatStore.META_LAST_ALERT_SEQ))
    }

    @Test fun `모르는 type은 그리지 않되 번호를 올린다`() = runBlocking {
        store.applyFrame(ServerFrame.Text(1, false, "글"), t0)
        store.applyFrame(ServerFrame.UnknownNumbered(2, false, "future_thing"), t0 + 1)
        assertEquals(2L, store.lastSeq())
        // 알림이 떴을지 모르므로 울렸을 수 있는 번호에 넣는다.
        assertEquals("2", store.meta(ChatStore.META_LAST_ALERT_SEQ))
        assertEquals(1, items(1).size)
    }

    @Test fun `alert가 가리키는 줄 - 루시아의 글이나 열린 카드만`() = runBlocking {
        store.applyFrame(ServerFrame.Text(5, false, "브리핑이에요", alert = false), t0)
        store.applyFrame(ServerFrame.Sent(6, false, "고마워", "app:cl-desk", "데스크톱 앱", null), t0 + 1)
        store.applyFrame(ServerFrame.CardOpen(7, false, "app:*:aaaaaaaaaaaa", pending, alert = false), t0 + 2)
        store.applyFrame(ServerFrame.CardOpen(8, false, "app:*:bbbbbbbbbbbb", pending, alert = false), t0 + 3)
        store.applyFrame(ServerFrame.CardClosed(9, false, "app:*:bbbbbbbbbbbb", closed), t0 + 4)
        items(4)
        assertEquals("브리핑이에요", store.alertable(5)?.body)
        assertEquals(null, store.alertable(6))
        assertEquals("app:*:aaaaaaaaaaaa", store.alertable(7)?.ref)
        // 이미 닫힌 카드는 알리지 않는다.
        assertEquals(null, store.alertable(8))
        assertEquals(null, store.alertable(99))
    }

    @Test fun `기록의 새 모양 - 조각마다 한 줄, 파일은 마지막 조각에`() = runBlocking {
        store.importHistory(listOf(HistoryItem(
            1203, "2026-11-22T00:10:09+00:00", "lucia", "app:cl-1", "정리해서 드릴게요.\n\n한 장으로 줄였어요.",
            parts = listOf("정리해서 드릴게요.", "한 장으로 줄였어요."), files = listOf(doc),
        )))
        val rows = items(2)
        assertEquals(listOf("정리해서 드릴게요.", "한 장으로 줄였어요."), rows.map { it.body })
        assertEquals(listOf(Kind.TEXT, Kind.FILES), rows.map { it.kind })
        assertEquals(listOf("f_9c1e0a7b2d4f"), rows[1].files.map { it.id })
        // 같은 기록을 다시 받아도 다시 넣지 않는다.
        assertEquals(0, store.importHistory(listOf(HistoryItem(1203, "2026-11-22T00:10:09+00:00", "lucia", "app:cl-1", "정리해서 드릴게요.\n\n한 장으로 줄였어요."))))
    }

    @Test fun `예전 모양으로 받은 기록은 새 모양으로 다시 받아도 한 번만 보인다`() = runBlocking {
        store.importHistory(listOf(history(1203, "2026-11-22T00:10:09+00:00", "lucia", "하나\n\n둘")))
        items(1)
        val again = HistoryItem(1203, "2026-11-22T00:10:09+00:00", "lucia", "app:cl-1", "하나\n\n둘", parts = listOf("하나", "둘"))
        assertEquals(0, store.importHistory(listOf(again)))
    }

    @Test fun `sent와 기록의 같은 말 - 설명 줄이 더 붙고 시각이 늦어도 한 번만 보인다`() = runBlocking {
        store.applyFrame(
            ServerFrame.Sent(63, false, "이거 번역해 줘", "app:cl-desk", "데스크톱 앱", null,
                files = listOf(doc), contextNote = "[고른 글 120자 함께 보냄 · msedge.exe]"),
            ChatStore.parseInstant("2026-11-22T08:12:03+00:00"),
        )
        items(1)
        // 예전 모양: 파일 설명 줄과 고른 글 표지가 본문에 붙어 있다. 앞의 일을 하느라 기록의 시각이 40초 늦다.
        val old = HistoryItem(500, "2026-11-22T08:12:43+00:00", "user", "app:cl-desk",
            "이거 번역해 줘\n[첨부: 원문.md]\n[고른 글 120자 함께 보냄 · msedge.exe]")
        assertEquals(0, store.importHistory(listOf(old)))
        // 새 모양: 파일 ID로 맞춘다.
        val shaped = HistoryItem(501, "2026-11-22T08:12:43+00:00", "user", "app:cl-desk",
            "다른 글\n[고른 글 120자 함께 보냄 · msedge.exe]", files = listOf(doc))
        assertEquals(0, store.importHistory(listOf(shaped)))
        assertEquals(1, items(1).size)
    }

    @Test fun `기록의 고른 글 표지는 글에서 떼어 표지로 둔다`() = runBlocking {
        store.importHistory(listOf(history(7, "2026-11-22T08:12:43+00:00", "user", "요약해 줘\n[고른 글 4000자 함께 보냄]", conv = "app:cl-desk")))
        val row = items(1).single()
        assertEquals("요약해 줘", row.body)
        assertEquals("[고른 글 4000자 함께 보냄]", row.contextNote)
    }

    @Test fun `기록에 보인 지워진 파일은 받을 수 없는 파일로 남는다`() = runBlocking {
        val gone = FileRef("f_0a1b2c3d4e5f", "옛.md", 90, "text/markdown", "text", gone = true, reachable = false)
        store.importHistory(listOf(HistoryItem(9, "2026-11-22T00:10:09+00:00", "lucia", "app:cl-1", "예전 파일이에요", files = listOf(gone))))
        val file = items(1).single().files.single()
        assertTrue(file.gone)
        assertFalse(file.available)
    }
}
