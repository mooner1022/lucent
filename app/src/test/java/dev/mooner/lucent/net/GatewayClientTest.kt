package dev.mooner.lucent.net

import dev.mooner.lucent.data.Route
import dev.mooner.lucent.protocol.ClientFrames
import dev.mooner.lucent.protocol.ErrorCodes
import dev.mooner.lucent.protocol.InvokeReply
import dev.mooner.lucent.protocol.ServerFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GatewayClientTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private val received = LinkedBlockingQueue<String>()

    private class Config(
        val base: String,
        var token: String? = "lucia_test",
        var resume: Long? = null,
    ) : GatewayConfig {
        var connected: Route? = null
        override fun token() = token
        override fun routes() = listOf(RouteBase(Route.HOME, base))
        override suspend fun resume() = resume
        override fun onConnected(route: Route) { connected = route }
    }

    private val welcome = """{"type":"welcome","v":2,"client":"cl-1","name":"시험","role":"app","conversation":"app:cl-1","seq":3,
        "scopes":[],"methods":["history"],"features":[],"notify_target":false,"push":{"available":false,"registered":false},
        "capabilities":{"attachments":true}}"""

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        scope.cancel()
        // 열린 WebSocket이 남아 있어도 시험이 멈추지 않게 짧게 기다리고 넘어간다.
        runCatching { server.shutdown() }
    }

    private fun base() = server.url("/").toString().trimEnd('/')

    private fun client(
        config: Config,
        features: List<String> = emptyList(),
        tools: List<JsonObject> = emptyList(),
        onInvoke: (suspend (ServerFrame.Invoke) -> InvokeReply)? = null,
        deadlineMs: Long = GatewayClient.INVOKE_DEADLINE_MS,
    ) = GatewayClient(OkHttpClient(), scope, config, { features }, { tools }, onInvoke, "LucentTest", deadlineMs)

    private fun script(onHello: (WebSocket) -> Unit) = MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            received.add(text)
            if ("\"type\":\"hello\"" in text) onHello(webSocket)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = Unit
    })

    private fun <T> await(block: suspend () -> T): T = runBlocking { withTimeout(8_000) { block() } }

    @Test fun `hello를 보내고 welcome 뒤의 프레임을 도착 순서대로 건넨다`() {
        server.enqueue(script { ws ->
            ws.send(welcome)
            ws.send("""{"type":"text","seq":4,"body":"안녕하세요"}""")
            ws.send("""{"type":"hologram"}""") // 모르는 type은 버린다
            ws.send("""{"type":"note","seq":5,"body":"찾는 중"}""")
        })
        val config = Config(base(), resume = 3)
        val gw = client(config, features = listOf("location"))
        gw.start()

        val first = await { gw.frames.receive() }
        assertTrue(first is ServerFrame.WelcomeFrame)
        val text = await { gw.frames.receive() } as ServerFrame.Text
        assertEquals("안녕하세요", text.body)
        val note = await { gw.frames.receive() } as ServerFrame.Note
        assertEquals(5, note.seq)

        val hello = received.poll(3, TimeUnit.SECONDS)!!
        assertTrue(hello.contains("\"token\":\"lucia_test\""))
        assertTrue(hello.contains("\"resume\":3"))
        assertTrue(hello.contains("\"location\""))
        val state = gw.state.value as LinkState.Online
        assertEquals("cl-1", state.welcome.client)
        assertEquals(Route.HOME, config.connected)
        gw.stop()
    }

    @Test fun `처음 연결은 resume이 없다`() {
        server.enqueue(script { it.send(welcome) })
        val gw = client(Config(base(), resume = null))
        gw.start()
        await { gw.frames.receive() }
        val hello = received.poll(3, TimeUnit.SECONDS)!!
        assertTrue(!hello.contains("resume"))
        gw.stop()
    }

    @Test fun `unauthorized면 다시 붙지 않는다`() {
        server.enqueue(script { ws -> ws.send("""{"type":"error","code":"unauthorized","message":"x"}"""); ws.close(1000, null) })
        val gw = client(Config(base()))
        gw.start()
        val state = await { gw.state.first { it is LinkState.Blocked } } as LinkState.Blocked
        assertEquals(ErrorCodes.UNAUTHORIZED, state.code)
        Thread.sleep(1_800) // 백오프 첫 시도(1초 안팎)를 넘겨도 다시 붙지 않아야 한다.
        assertEquals(1, server.requestCount)
        gw.stop()
    }

    @Test fun `too_many_attempts면 쉬는 상태가 된다`() {
        server.enqueue(script { ws -> ws.send("""{"type":"error","code":"too_many_attempts","message":"x"}"""); ws.close(1000, null) })
        val gw = client(Config(base()))
        gw.start()
        val state = await { gw.state.first { it is LinkState.Throttled } } as LinkState.Throttled
        assertTrue(state.until > System.currentTimeMillis() + 9 * 60_000)
        gw.stop()
    }

    @Test fun `서버가 닫으면 백오프 뒤 다시 붙는다`() {
        server.enqueue(script { ws -> ws.send(welcome); ws.close(1001, "재시작") })
        server.enqueue(script { ws -> ws.send(welcome) })
        val gw = client(Config(base()))
        gw.start()
        await { gw.state.first { it is LinkState.Online } }
        await { gw.state.first { it is LinkState.Waiting } }
        // 두 번째 연결은 약 1초 뒤에 열린다.
        await { while (server.requestCount < 2) kotlinx.coroutines.delay(50) }
        assertEquals(2, server.requestCount)
        gw.stop()
    }

    @Test fun `call은 같은 id의 result로 풀린다`() {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
                when {
                    "\"type\":\"hello\"" in text -> webSocket.send(welcome)
                    "\"type\":\"call\"" in text -> {
                        val id = Regex("\"id\":\"([^\"]+)\"").find(text)!!.groupValues[1]
                        webSocket.send("""{"type":"result","id":"$id","ok":true,"data":{"items":[],"more":false}}""")
                    }
                }
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }))
        val gw = client(Config(base()))
        gw.start()
        await { gw.state.first { it is LinkState.Online } }
        val result = await { gw.call("history") }
        assertTrue(result.ok)
        gw.stop()
    }

    @Test fun `연결이 없으면 call은 바로 실패한다`() {
        val gw = client(Config(base()))
        try {
            await { gw.call("history") }
            throw AssertionError("실패해야 한다")
        } catch (_: GatewayClient.ConnectionLost) {
        }
    }

    @Test fun `ack와 presence와 message가 서버에 닿는다`() {
        server.enqueue(script { it.send(welcome) })
        val gw = client(Config(base()))
        gw.start()
        await { gw.state.first { it is LinkState.Online } }
        received.poll(3, TimeUnit.SECONDS) // hello
        assertTrue(gw.sendAck(7))
        gw.sendPresence(false)
        assertTrue(gw.send(ClientFrames.message("안녕", emptyList(), "m-1")))
        assertEquals("""{"type":"ack","seq":7}""", received.poll(3, TimeUnit.SECONDS))
        assertEquals("""{"type":"presence","state":"background"}""", received.poll(3, TimeUnit.SECONDS))
        assertTrue(received.poll(3, TimeUnit.SECONDS)!!.contains("\"id\":\"m-1\""))
        gw.stop()
    }

    @Test fun `토큰이 없으면 연결하지 않고 막힌 상태가 된다`() {
        val gw = client(Config(base(), token = null))
        gw.start()
        val state = await { gw.state.first { it is LinkState.Blocked } } as LinkState.Blocked
        assertEquals(ErrorCodes.UNAUTHORIZED, state.code)
        assertEquals(0, server.requestCount)
    }

    @Test fun `처리기가 없으면 데몬의 invoke에 지원하지 않는다고 답한다`() {
        server.enqueue(script { ws -> ws.send(welcome); ws.send("""{"type":"invoke","id":"d-1","method":"location.get","params":{}}""") })
        val gw = client(Config(base()))
        gw.start()
        await { gw.state.first { it is LinkState.Online } }
        received.poll(3, TimeUnit.SECONDS) // hello
        val answer = received.poll(3, TimeUnit.SECONDS)!!
        assertTrue(answer.contains("\"id\":\"d-1\"") && answer.contains("unavailable"))
        gw.stop()
    }

    private fun parse(text: String) = dev.mooner.lucent.protocol.LucentJson.parseToJsonElement(text).jsonObject

    @Test fun `hello에 내주는 기능을 싣고 invoke를 처리기에 넘겨 같은 id로 답한다`() {
        server.enqueue(script { ws -> ws.send(welcome); ws.send("""{"type":"invoke","id":"d-2","method":"location.get","params":{"precise":true}}""") })
        val tool = buildJsonObject { put("name", "location.get"); put("description", "위치") }
        var seen: ServerFrame.Invoke? = null
        val gw = client(Config(base()), tools = listOf(tool), onInvoke = { frame ->
            seen = frame
            InvokeReply.Ok(buildJsonObject { put("lat", 37.5) })
        })
        gw.start()
        await { gw.state.first { it is LinkState.Online } }
        val hello = parse(received.poll(3, TimeUnit.SECONDS)!!)
        assertEquals("location.get", hello["tools"]!!.jsonArray[0].jsonObject["name"]!!.jsonPrimitive.content)
        val answer = parse(received.poll(3, TimeUnit.SECONDS)!!)
        assertEquals("result", answer["type"]!!.jsonPrimitive.content)
        assertEquals("d-2", answer["id"]!!.jsonPrimitive.content)
        assertEquals(true, answer["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("37.5", answer["data"]!!.jsonObject["lat"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), seen!!.params!!.jsonObject["precise"])
        // invoke는 프레임 흐름에 싣지 않는다.
        assertTrue(await { gw.frames.receive() } is ServerFrame.WelcomeFrame)
        assertTrue(gw.frames.tryReceive().isFailure)
        gw.stop()
    }

    @Test fun `내주는 기능이 없으면 hello에 tools를 싣지 않는다`() {
        server.enqueue(script { it.send(welcome) })
        val gw = client(Config(base()))
        gw.start()
        await { gw.state.first { it is LinkState.Online } }
        assertTrue(!received.poll(3, TimeUnit.SECONDS)!!.contains("\"tools\""))
        gw.stop()
    }

    @Test fun `처리기가 늦으면 시간 안에 unavailable로 한 번만 답한다`() {
        server.enqueue(script { ws -> ws.send(welcome); ws.send("""{"type":"invoke","id":"d-3","method":"slow.get"}""") })
        val gw = client(Config(base()), onInvoke = { delay(5_000); InvokeReply.Ok(JsonObject(emptyMap())) }, deadlineMs = 300)
        gw.start()
        await { gw.state.first { it is LinkState.Online } }
        received.poll(3, TimeUnit.SECONDS) // hello
        val answer = parse(received.poll(3, TimeUnit.SECONDS)!!)
        assertEquals("d-3", answer["id"]!!.jsonPrimitive.content)
        assertEquals(ErrorCodes.UNAVAILABLE, answer["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(null, received.poll(1, TimeUnit.SECONDS))
        gw.stop()
    }

    @Test fun `처리기가 실패하면 failed로 답하고 너무 큰 답은 보내지 않는다`() {
        server.enqueue(script { ws ->
            ws.send(welcome)
            ws.send("""{"type":"invoke","id":"d-4","method":"broken.get"}""")
            ws.send("""{"type":"invoke","id":"d-5","method":"huge.get"}""")
        })
        val gw = client(Config(base()), onInvoke = { frame ->
            if (frame.method == "broken.get") error("고장")
            InvokeReply.Ok(buildJsonObject { put("text", "가".repeat(30_000)) })
        })
        gw.start()
        await { gw.state.first { it is LinkState.Online } }
        received.poll(3, TimeUnit.SECONDS) // hello
        val answers = listOf(received.poll(3, TimeUnit.SECONDS)!!, received.poll(3, TimeUnit.SECONDS)!!).map(::parse).associateBy { it["id"]!!.jsonPrimitive.content }
        assertEquals(ErrorCodes.FAILED, answers["d-4"]!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(ErrorCodes.FAILED, answers["d-5"]!!["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        gw.stop()
    }

    @Test fun `번호가 있는 모르는 프레임과 sent, seen, alert는 버리지 않고 건넨다`() {
        server.enqueue(script { ws ->
            ws.send(welcome)
            ws.send("""{"type":"sent","seq":6,"body":"내일 일정 알려 줘","conversation":"app:cl-2","from":{"client":"cl-2","name":"데스크톱"}}""")
            ws.send("""{"type":"notes","rev":4}""") // 번호 없는 모르는 type은 버린다
            ws.send("""{"type":"future_thing","seq":7}""")
            ws.send("""{"type":"seen","seq":7}""")
            ws.send("""{"type":"alert","seq":6}""")
        })
        val gw = client(Config(base()))
        gw.start()
        assertTrue(await { gw.frames.receive() } is ServerFrame.WelcomeFrame)
        assertEquals("데스크톱", (await { gw.frames.receive() } as ServerFrame.Sent).fromName)
        assertEquals(7L, (await { gw.frames.receive() } as ServerFrame.UnknownNumbered).seq)
        assertEquals(7L, (await { gw.frames.receive() } as ServerFrame.Seen).seq)
        assertEquals(6L, (await { gw.frames.receive() } as ServerFrame.Alert).seq)
        gw.stop()
    }

    @Test fun `읽을 수 없는 프레임이 와도 연결은 그대로다`() {
        server.enqueue(script { ws ->
            ws.send(welcome)
            ws.send("""{"type":{"a":1},"seq":4}""")
            ws.send("""{"type":"text","seq":4,"replay":["x"],"body":"다음 말"}""")
        })
        val gw = client(Config(base()))
        gw.start()
        assertTrue(await { gw.frames.receive() } is ServerFrame.WelcomeFrame)
        val text = await { gw.frames.receive() } as ServerFrame.Text
        assertEquals("다음 말", text.body)
        assertTrue(gw.state.value is LinkState.Online)
        assertEquals(1, server.requestCount)
        gw.stop()
    }

    @Test fun `막힌 동안에는 사용자가 깨울 때만 바로 다시 시도한다`() {
        server.enqueue(script { ws -> ws.send("""{"type":"error","code":"too_many_attempts","message":"x"}"""); ws.close(1000, null) })
        server.enqueue(script { ws -> ws.send(welcome) })
        val gw = client(Config(base()))
        gw.start()
        await { gw.state.first { it is LinkState.Throttled } }
        // 앞으로 오거나 망이 바뀌어 깨우는 것은 10분 기다림을 줄이지 않는다.
        gw.start()
        gw.wake()
        Thread.sleep(700)
        assertTrue(gw.state.value is LinkState.Throttled)
        assertEquals(1, server.requestCount)
        gw.wake(byUser = true)
        await { gw.state.first { it is LinkState.Online } }
        gw.stop()
    }

    @Test fun `연결돼 있을 때 온 깨우기는 다음 기다림을 줄이지 않는다`() {
        var open: WebSocket? = null
        server.enqueue(script { ws -> open = ws; ws.send(welcome) })
        val gw = client(Config(base()))
        gw.start()
        await { gw.state.first { it is LinkState.Online } }
        gw.start()
        gw.wake(byUser = true)
        val ws = open!!
        ws.send("""{"type":"error","code":"too_many_attempts","message":"x"}""")
        ws.close(1000, null)
        await { gw.state.first { it is LinkState.Throttled } }
        Thread.sleep(700)
        assertTrue(gw.state.value is LinkState.Throttled)
        assertEquals(1, server.requestCount)
        gw.stop()
    }

    @Test fun `reconnectNow는 기다리지 않고 다시 붙는다`() {
        server.enqueue(script { ws -> ws.send(welcome) })
        server.enqueue(script { ws -> ws.send(welcome) })
        val gw = client(Config(base()))
        gw.start()
        await { gw.state.first { it is LinkState.Online } }
        val at = System.currentTimeMillis()
        gw.reconnectNow()
        await { while (server.requestCount < 2) delay(20) }
        // 백오프의 첫 기다림은 750ms보다 길다.
        assertTrue(System.currentTimeMillis() - at < 600)
        await { gw.state.first { it is LinkState.Online } }
        gw.stop()
    }
}
