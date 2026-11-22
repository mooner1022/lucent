package dev.mooner.lucent.protocol

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 사양서(docs/app-api.md)의 예시 프레임을 그대로 넣어 본다. */
class ProtocolTest {

    @Test fun `welcome - 사양 5_3의 예시`() {
        val frame = parseServerFrame(
            """{"type":"welcome","v":2,"client":"cl-3f9a0c12de","name":"휴대폰 앱","role":"app","conversation":"app:cl-3f9a0c12de",
              "seq":57,"scopes":[],"methods":["commands","command","history","push.register","push.unregister"],"features":["location"],
              "notify_target":true,"push":{"available":true,"registered":false},
              "capabilities":{"max_message_chars":8000,"buttons":true,"edit":true,"prompts":true,"attachments":true,
              "max_upload_bytes":31457280,"max_message_files":10},"앞으로_생길_필드":1}""",
        ) as ServerFrame.WelcomeFrame
        val w = frame.welcome
        assertEquals(2, w.v)
        assertEquals("cl-3f9a0c12de", w.client)
        assertEquals(57, w.seq)
        assertTrue(w.notifyTarget)
        assertTrue(w.push.available)
        assertFalse(w.push.registered)
        assertEquals(31_457_280L, w.capabilities.maxUploadBytes)
        assertTrue(w.canCall("command"))
        assertFalse(w.hasScope("security_mode"))
    }

    @Test fun `번호 붙는 프레임과 replay 표시`() {
        val text = parseServerFrame("""{"type":"text","seq":58,"body":"오늘 3시에 회의가 있어요."}""") as ServerFrame.Text
        assertEquals(58, text.seq)
        assertFalse(text.replay)
        val replayed = parseServerFrame("""{"type":"text","seq":4,"replay":true,"body":"좋은 아침이에요."}""") as ServerFrame.Text
        assertTrue(replayed.replay)
        val note = parseServerFrame("""{"type":"note","seq":59,"body":"메일함을 확인하고 있어요"}""") as ServerFrame.Note
        assertEquals("메일함을 확인하고 있어요", note.body)
    }

    @Test fun `files 프레임`() {
        val f = parseServerFrame(
            """{"type":"files","seq":60,"body":"정리했어요","files":[{"id":"f_9c1e0a7b2d4f","name":"정리.md","size":1834,"mime":"text/markdown","kind":"text"}]}""",
        ) as ServerFrame.Files
        assertEquals("f_9c1e0a7b2d4f", f.files.single().id)
        assertEquals(1834, f.files.single().size)
        assertFalse(f.files.single().isImage)
    }

    @Test fun `card와 card_closed`() {
        val json = """{"type":"card","seq":61,"ref":"app:cl-3f9a0c12de:5b0e1f3a9c2d","card":{
            "headline":"확인해 주세요","title":"메일 보내기",
            "fields":[{"name":"받는 사람","value":"a@example.com","inline":true}],
            "body":"안녕하세요, …","footer":"만료 11-19 15:00 · apr-1a2b","tone":"pending",
            "buttons":[{"action":"approve:apr-1a2b","label":"보내기","primary":true,"prompt":null},
                       {"action":"reject:apr-1a2b","label":"거절","primary":false,"prompt":null}]}}"""
        val open = parseServerFrame(json) as ServerFrame.CardOpen
        assertEquals("app:cl-3f9a0c12de:5b0e1f3a9c2d", open.ref)
        assertTrue(open.card.isPending)
        assertEquals(2, open.card.buttons.size)
        assertTrue(open.card.buttons[0].primary)
        assertNull(open.card.buttons[0].prompt)
        assertTrue(open.card.fields.single().inline)

        val closed = parseServerFrame(
            """{"type":"card_closed","seq":62,"ref":"app:cl-3f9a0c12de:5b0e1f3a9c2d","card":{"headline":"✅ 보냈습니다","tone":"success","buttons":[]}}""",
        ) as ServerFrame.CardClosed
        assertFalse(closed.card.isPending)
        assertEquals("✅ 보냈습니다", closed.card.headline)
    }

    @Test fun `값을 받는 버튼`() {
        val open = parseServerFrame(
            """{"type":"card","seq":1,"ref":"r","card":{"tone":"pending","buttons":[{"action":"code","label":"입력",
               "prompt":{"title":"인증 코드","label":"6자리","placeholder":"123456","min_len":6,"max_len":6}}]}}""",
        ) as ServerFrame.CardOpen
        val prompt = open.card.buttons.single().prompt!!
        assertEquals(6, prompt.minLen)
        assertEquals(6, prompt.maxLen)
        assertEquals("123456", prompt.placeholder)
    }

    @Test fun `resumed, accepted, result, error, pong, typing`() {
        val r = parseServerFrame("""{"type":"resumed","from":41,"to":57,"last":57,"truncated":false}""") as ServerFrame.Resumed
        assertEquals(57, r.last); assertFalse(r.truncated)
        assertEquals("m1", (parseServerFrame("""{"type":"accepted","id":"m1"}""") as ServerFrame.Accepted).id)
        assertNull((parseServerFrame("""{"type":"accepted","id":null}""") as ServerFrame.Accepted).id)
        val ok = parseServerFrame("""{"type":"result","id":"c1","ok":true,"data":{"items":[],"more":false}}""") as ServerFrame.Result
        assertTrue(ok.ok)
        val bad = parseServerFrame("""{"type":"result","id":"c2","ok":false,"error":{"code":"forbidden","message":"x"}}""") as ServerFrame.Result
        assertEquals("forbidden", bad.error?.code)
        assertEquals("bad_file", (parseServerFrame("""{"type":"error","code":"bad_file","message":"f_1"}""") as ServerFrame.Error).code)
        assertTrue(parseServerFrame("""{"type":"pong"}""") === ServerFrame.Pong)
        assertTrue(parseServerFrame("""{"type":"typing"}""") === ServerFrame.Typing)
    }

    @Test fun `모르는 type은 오류가 아니라 버리는 프레임이다`() {
        val f = parseServerFrame("""{"type":"hologram","x":1}""")
        assertTrue(f is ServerFrame.Unknown)
    }

    @Test fun `깨진 JSON과 type 없는 프레임은 파싱 오류다`() {
        try { parseServerFrame("{깨짐"); fail() } catch (_: FrameParseException) {}
        try { parseServerFrame("""{"seq":1}"""); fail() } catch (_: FrameParseException) {}
        try { parseServerFrame("""{"type":"text","body":"seq 없음"}"""); fail() } catch (_: FrameParseException) {}
    }

    @Test fun `history 페이지`() {
        val page = LucentJson.decodeFromString(
            HistoryPage.serializer(),
            """{"items":[{"id":1201,"at":"2026-11-19T00:10:00+00:00","from":"user","conversation":"app:cl-3f9a0c12de","body":"내일 일정 알려 줘"},
                         {"id":1202,"at":"2026-11-19T00:10:09+00:00","from":"lucia","conversation":null,"body":"내일은…"}],"more":true}""",
        )
        assertEquals(2, page.items.size)
        assertTrue(page.more)
        assertNull(page.items[1].conversation)
    }

    @Test fun `hello는 resume이 null이면 싣지 않는다`() {
        val first = LucentJson.parseToJsonElement(ClientFrames.hello("lucia_x", null, emptyList())).jsonObject
        assertFalse("resume" in first)
        val again = LucentJson.parseToJsonElement(ClientFrames.hello("lucia_x", 41, listOf("location"))).jsonObject
        assertEquals("41", again["resume"]!!.jsonPrimitive.content)
        assertEquals("hello", again["type"]!!.jsonPrimitive.content)
    }

    @Test fun `message, press, ack, presence, call 인코딩`() {
        val m = LucentJson.parseToJsonElement(ClientFrames.message("안녕", listOf("f_1"), "m-1")).jsonObject
        assertEquals("message", m["type"]!!.jsonPrimitive.content)
        assertEquals("m-1", m["id"]!!.jsonPrimitive.content)
        val noFiles = LucentJson.parseToJsonElement(ClientFrames.message("안녕", emptyList(), "m-2")).jsonObject
        assertFalse("files" in noFiles)
        val p = LucentJson.parseToJsonElement(ClientFrames.press("ref", "approve:x", "123456")).jsonObject
        assertEquals("123456", p["input"]!!.jsonPrimitive.content)
        assertFalse("input" in LucentJson.parseToJsonElement(ClientFrames.press("ref", "a", null)).jsonObject)
        assertEquals("""{"type":"ack","seq":62}""", ClientFrames.ack(62))
        assertEquals("""{"type":"presence","state":"background"}""", ClientFrames.presence(false))
        assertEquals("""{"type":"ping"}""", ClientFrames.ping())
    }

    // ------------------------------------------------------------------ 이모지·스티커(docs/app-expressions.md)

    @Test fun `welcome의 expressions - 없으면 그 기능이 없는 데몬이다`() {
        val with = parseServerFrame(
            """{"type":"welcome","v":2,"client":"cl-1","seq":1,"expressions":{"version":"c41d9e07a2b8","emoji":24,"stickers":12}}""",
        ) as ServerFrame.WelcomeFrame
        assertEquals("c41d9e07a2b8", with.welcome.expressions?.version)
        assertEquals(24, with.welcome.expressions?.emoji)
        assertEquals(12, with.welcome.expressions?.stickers)
        val without = parseServerFrame("""{"type":"welcome","v":2,"client":"cl-1","seq":1}""") as ServerFrame.WelcomeFrame
        assertNull(without.welcome.expressions)
        // 저장했다 다시 읽어도 없는 것은 없는 채다.
        val stored = LucentJson.encodeToString(Welcome.serializer(), without.welcome)
        assertNull(LucentJson.decodeFromString(Welcome.serializer(), stored).expressions)
    }

    @Test fun `sticker는 번호 붙는 프레임, expressions는 번호 없는 프레임이다`() {
        val frame = parseServerFrame("""{"type":"sticker","seq":64,"sticker":{"id":"s_7a1c0e9b3f52","name":"축하"}}""")
        assertTrue(frame is ServerFrame.Numbered)
        val sticker = frame as ServerFrame.Sticker
        assertEquals(64, sticker.seq)
        assertEquals("s_7a1c0e9b3f52", sticker.id)
        assertEquals("축하", sticker.name)
        val replayed = parseServerFrame("""{"type":"sticker","seq":7,"replay":true,"sticker":{"id":"s_1","name":"꾸벅"}}""") as ServerFrame.Sticker
        assertTrue(replayed.replay)
        // 번호가 없으면 읽지 못한 프레임이다(다른 번호 붙는 프레임과 같다).
        try {
            parseServerFrame("""{"type":"sticker","sticker":{"id":"s_1","name":"꾸벅"}}""")
            fail("seq 없는 sticker를 받았다")
        } catch (_: FrameParseException) {
        }
        val changed = parseServerFrame("""{"type":"expressions","version":"d9e3"}""") as ServerFrame.Expressions
        assertEquals("d9e3", changed.version)
        assertFalse((changed as ServerFrame) is ServerFrame.Numbered)
    }

    // ------------------------------------------------------------------ 프로필 그림(사양 8.7)

    @Test fun `welcome의 profile - 없으면 그 기능이 없는 데몬, avatar가 null이면 정한 그림이 없다`() {
        val with = parseServerFrame(
            """{"type":"welcome","v":2,"client":"cl-1","seq":1,"profile":{"avatar":{"id":"a_cfc0dacd5dcd71abf496c418",
               "mime":"image/png","width":800,"height":800,"size":579183,"updated_at":"2026-11-20T04:43:07Z"}}}""",
        ) as ServerFrame.WelcomeFrame
        val avatar = with.welcome.profile?.avatar
        assertEquals("a_cfc0dacd5dcd71abf496c418", avatar?.id)
        assertEquals("image/png", avatar?.mime)
        assertEquals(800, avatar?.width)
        assertEquals(579183L, avatar?.size)
        assertEquals("2026-11-20T04:43:07Z", avatar?.updatedAt)
        assertTrue(avatar!!.usable)

        val none = parseServerFrame("""{"type":"welcome","v":2,"client":"cl-1","seq":1,"profile":{"avatar":null}}""") as ServerFrame.WelcomeFrame
        assertNotNull(none.welcome.profile)
        assertNull(none.welcome.profile?.avatar)

        val old = parseServerFrame("""{"type":"welcome","v":2,"client":"cl-1","seq":1}""") as ServerFrame.WelcomeFrame
        assertNull(old.welcome.profile)
        // 저장했다 다시 읽어도 셋이 구별된다.
        fun stored(w: Welcome) = LucentJson.decodeFromString(Welcome.serializer(), LucentJson.encodeToString(Welcome.serializer(), w))
        assertNull(stored(old.welcome).profile)
        assertNotNull(stored(none.welcome).profile)
        assertNull(stored(none.welcome).profile?.avatar)
        assertEquals("a_cfc0dacd5dcd71abf496c418", stored(with.welcome).profile?.avatar?.id)
    }

    @Test fun `profile은 번호 없는 프레임이다 - 그림이 바뀌거나 없어진다`() {
        val changed = parseServerFrame(
            """{"type":"profile","avatar":{"id":"a_0123456789abcdef01234567","mime":"image/jpeg","width":512,"height":512,"size":48211,"updated_at":"2026-11-20T05:00:00Z"}}""",
        ) as ServerFrame.ProfileChanged
        assertEquals("a_0123456789abcdef01234567", changed.avatar?.id)
        assertFalse((changed as ServerFrame) is ServerFrame.Numbered)
        val cleared = parseServerFrame("""{"type":"profile","avatar":null}""") as ServerFrame.ProfileChanged
        assertNull(cleared.avatar)
        // 필드가 빠져도 없는 것으로 읽는다.
        assertNull((parseServerFrame("""{"type":"profile"}""") as ServerFrame.ProfileChanged).avatar)
    }

    @Test fun `프로필 그림의 id는 a_와 16진수 24자여야 쓴다 - 파일 이름과 주소에 들어간다`() {
        assertTrue(Avatar(id = "a_cfc0dacd5dcd71abf496c418").usable)
        for (bad in listOf("", "a_", "a_cfc0", "x_cfc0dacd5dcd71abf496c418", "a_cfc0dacd5dcd71abf496c41g", "a_../../../../etc/passwd000", "a_cfc0dacd5dcd71abf496c4180", "../a_cfc0dacd5dcd71abf496c418"))
            assertFalse(bad, Avatar(id = bad).usable)
    }

    @Test fun `message의 sticker - 스티커만 보내면 글을 싣지 않는다`() {
        val only = LucentJson.parseToJsonElement(ClientFrames.message("", emptyList(), "m-1", "s_7a1c0e9b3f52")).jsonObject
        assertEquals("s_7a1c0e9b3f52", only["sticker"]!!.jsonPrimitive.content)
        assertFalse("text" in only)
        assertEquals("m-1", only["id"]!!.jsonPrimitive.content)
        val both = LucentJson.parseToJsonElement(ClientFrames.message("나 합격했어!", emptyList(), "m-2", "s_7a1c0e9b3f52")).jsonObject
        assertEquals("나 합격했어!", both["text"]!!.jsonPrimitive.content)
        assertEquals("s_7a1c0e9b3f52", both["sticker"]!!.jsonPrimitive.content)
        val plain = LucentJson.parseToJsonElement(ClientFrames.message("안녕", emptyList(), "m-3")).jsonObject
        assertFalse("sticker" in plain)
        assertFalse(ErrorCodes.endsSession(ErrorCodes.BAD_STICKER))
    }

    @Test fun `오류 코드 풀이와 세션 종료 판단`() {
        assertTrue(ErrorCodes.endsSession(ErrorCodes.UNAUTHORIZED))
        assertTrue(ErrorCodes.endsSession(ErrorCodes.UNSUPPORTED_ROLE))
        assertFalse(ErrorCodes.endsSession(ErrorCodes.TOO_MANY_ATTEMPTS))
        assertFalse(ErrorCodes.endsSession(ErrorCodes.CLOSED))
        assertEquals("fallback", describeError("모르는코드", "fallback"))
    }

    @Test fun `type이 글이 아니면 읽을 수 없는 프레임이고 replay가 글이 아니면 다시 받은 것이 아니다`() {
        for (bad in listOf("""{"type":{"a":1},"seq":1}""", """{"type":["text"],"seq":1}""", """{"type":null}""", """{"seq":1}""")) {
            try {
                parseServerFrame(bad)
                fail("읽혀서는 안 된다: $bad")
            } catch (_: FrameParseException) {
            }
        }
        val odd = parseServerFrame("""{"type":"text","seq":9,"body":"가","replay":{"a":1}}""") as ServerFrame.Text
        assertEquals(9, odd.seq)
        assertTrue(!odd.replay)
        val listed = parseServerFrame("""{"type":"resumed","from":1,"to":2,"last":2,"truncated":[true]}""") as ServerFrame.Resumed
        assertTrue(!listed.truncated)
    }

    // ------------------------------------------------------------ 결정 0064·0066·0067(app-api-changes.md 2026-11-21, 2026-11-22)

    @Test fun `welcome - 이름, seen, 한 대화, 기록 모양의 표시`() {
        val w = (parseServerFrame(
            """{"type":"welcome","v":2,"client":"cl-3f9a0c12de","seq":57,
              "profile":{"name":"달빛","avatar":null},
              "capabilities":{"max_message_chars":8000,"history_shape":2,"owner_files":true,"tool_when":true,"desk":true,
              "presence_away":true,"message_context":true,"seen":true,"shared_conversation":true}}""",
        ) as ServerFrame.WelcomeFrame).welcome
        assertEquals("달빛", w.profile?.name)
        assertTrue(w.capabilities.seen)
        assertTrue(w.capabilities.sharedConversation)
        assertEquals(2, w.capabilities.historyShape)
        // 표시가 없는 예전 데몬: seen을 보내지 않고 기록은 예전 모양으로 읽는다.
        val old = (parseServerFrame("""{"type":"welcome","v":2,"client":"cl-1","seq":1,"profile":{"avatar":null}}""") as ServerFrame.WelcomeFrame).welcome
        assertFalse(old.capabilities.seen)
        assertFalse(old.capabilities.sharedConversation)
        assertEquals(0, old.capabilities.historyShape)
        assertNull(old.profile?.name)
    }

    @Test fun `profile 프레임 - 늘 이름과 그림 전체가 온다`() {
        val frame = parseServerFrame("""{"type":"profile","name":"Lucia","avatar":null}""") as ServerFrame.ProfileChanged
        assertEquals("Lucia", frame.name)
        assertNull(frame.avatar)
        assertNull((parseServerFrame("""{"type":"profile","avatar":null}""") as ServerFrame.ProfileChanged).name)
    }

    @Test fun `alert false와 conversation - 필드가 없으면 울리고 이 앱의 대화다`() {
        val held = parseServerFrame("""{"type":"text","seq":92,"body":"정리했어요","conversation":"app:cl-desk","alert":false}""") as ServerFrame.Text
        assertFalse(held.alert)
        assertEquals("app:cl-desk", held.conversation)
        val plain = parseServerFrame("""{"type":"text","seq":93,"body":"네"}""") as ServerFrame.Text
        assertTrue(plain.alert)
        assertNull(plain.conversation)
        val card = parseServerFrame("""{"type":"card","seq":94,"ref":"app:*:5b0e1f3a9c2d","card":{"tone":"pending"},"alert":false}""") as ServerFrame.CardOpen
        assertFalse(card.alert)
        val sticker = parseServerFrame("""{"type":"sticker","seq":95,"sticker":{"id":"s_1","name":"축하"},"alert":false,"replay":true}""") as ServerFrame.Sticker
        assertFalse(sticker.alert)
        assertTrue(sticker.replay)
        val files = parseServerFrame("""{"type":"files","seq":96,"body":"","files":[{"id":"f_1","name":"a.md"}],"alert":"false"}""") as ServerFrame.Files
        // 글로 온 "false"도 놓아 두라는 뜻으로 읽는다. 그 밖의 값(엉뚱한 모양)은 울린다.
        assertFalse(files.alert)
        assertTrue((parseServerFrame("""{"type":"text","seq":97,"body":"","alert":{"x":1}}""") as ServerFrame.Text).alert)
    }

    @Test fun `sent - 다른 기기에서 한 내 말(사양 5_5의 예시)`() {
        val sent = parseServerFrame(
            """{"type": "sent", "seq": 63, "body": "이거 번역해 줘",
             "conversation": "app:cl-3f9a0c12de", "from": {"client": "cl-3f9a0c12de", "name": "데스크톱 앱"},
             "at": "2026-11-22T08:12:03.512300+00:00",
             "files": [{"id": "f_9c1e0a7b2d4f", "name": "원문.md", "size": 1834, "mime": "text/markdown", "kind": "text"}],
             "sticker": {"id": "s_7a1c0e9b3f52", "name": "축하"},
             "context_note": "[고른 글 120자 함께 보냄 · msedge.exe]"}""",
        ) as ServerFrame.Sent
        assertEquals(63, sent.seq)
        assertEquals("이거 번역해 줘", sent.body)
        assertEquals("app:cl-3f9a0c12de", sent.conversation)
        assertEquals("데스크톱 앱", sent.fromName)
        assertEquals("2026-11-22T08:12:03.512300+00:00", sent.at)
        assertEquals("f_9c1e0a7b2d4f", sent.files.single().id)
        assertEquals("s_7a1c0e9b3f52", sent.stickerId)
        assertEquals("축하", sent.stickerName)
        assertEquals("[고른 글 120자 함께 보냄 · msedge.exe]", sent.contextNote)
        // 붙은 것이 없으면 필드가 없다.
        val bare = parseServerFrame("""{"type":"sent","seq":64,"body":"네","from":{"client":"cl-1"}}""") as ServerFrame.Sent
        assertTrue(bare.files.isEmpty())
        assertNull(bare.stickerId)
        assertNull(bare.contextNote)
        assertNull(bare.fromName)
    }

    @Test fun `seen과 alert - 번호 없는 프레임`() {
        assertEquals(58L, (parseServerFrame("""{"type":"seen","seq":58}""") as ServerFrame.Seen).seq)
        assertEquals(92L, (parseServerFrame("""{"type":"alert","seq":92}""") as ServerFrame.Alert).seq)
        assertEquals("""{"type":"seen","seq":58}""", ClientFrames.seen(58))
    }

    @Test fun `모르는 type도 번호가 있으면 번호를 올려야 하는 프레임이다(5_4)`() {
        val unknown = parseServerFrame("""{"type":"future_thing","seq":70,"replay":true,"x":1}""")
        assertTrue(unknown is ServerFrame.UnknownNumbered)
        unknown as ServerFrame.UnknownNumbered
        assertEquals(70L, unknown.seq)
        assertTrue(unknown.replay)
        // 번호가 없으면 버리는 프레임이다(notes, tasks 등).
        assertTrue(parseServerFrame("""{"type":"notes","rev":45}""") is ServerFrame.Unknown)
        assertTrue(parseServerFrame("""{"type":"tasks"}""") is ServerFrame.Unknown)
    }

    @Test fun `기록의 새 모양 - 조각과 파일(사양 6_2의 예시)`() {
        val page = LucentJson.decodeFromString(
            HistoryPage.serializer(),
            """{"items":[{"id": 1203, "at": "2026-11-22T00:10:09+00:00", "from": "lucia", "conversation": "app:cl-3f9a0c12de",
             "body": "정리해서 드릴게요.\n\n한 장으로 줄였어요.",
             "parts": ["정리해서 드릴게요.", "한 장으로 줄였어요."],
             "files": [{"id": "f_9c1e0a7b2d4f", "name": "정리.md", "size": 1834, "mime": "text/markdown", "kind": "text", "reachable": true},
                       {"id": "f_0a1b2c3d4e5f", "name": "옛.md", "size": 90, "mime": "text/markdown", "kind": "text",
                        "gone": true, "reachable": false}]}],"more":false}""",
        )
        val item = page.items.single()
        assertEquals(listOf("정리해서 드릴게요.", "한 장으로 줄였어요."), item.parts)
        val (live, gone) = item.files!!
        assertTrue(live.available)
        assertTrue(gone.gone)
        assertFalse(gone.available)
        // 실시간 프레임의 파일에는 이 필드가 없다: 받을 수 있다.
        assertTrue(FileRef("f_1").available)
    }
}
