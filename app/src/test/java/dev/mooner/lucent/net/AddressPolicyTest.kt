package dev.mooner.lucent.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressPolicyTest {

    private fun ok(input: String): AddressPolicy.Result.Ok =
        AddressPolicy.parse(input) as? AddressPolicy.Result.Ok ?: error("받아져야 하는 주소: $input -> ${AddressPolicy.parse(input)}")

    private fun rejected(input: String) =
        assertTrue("거절돼야 하는 주소: $input", AddressPolicy.parse(input) is AddressPolicy.Result.Rejected)

    @Test fun `집 안 주소는 방식을 쓰지 않으면 http로 본다`() {
        val a = ok("192.168.0.10:8790")
        assertEquals("http://192.168.0.10:8790", a.base)
        assertTrue(a.cleartext)
    }

    @Test fun `공개 주소는 방식을 쓰지 않으면 https로 본다`() {
        val a = ok("lucia.example.com")
        assertEquals("https://lucia.example.com", a.base)
        assertFalse(a.cleartext)
    }

    @Test fun `ws와 wss는 http와 https로 바꾸고 경로는 지운다`() {
        assertEquals("http://10.0.0.5:8790", ok("ws://10.0.0.5:8790/v1/ws").base)
        assertEquals("https://lucia.example.com", ok("wss://lucia.example.com/v1/ws").base)
        assertEquals("https://lucia.example.com", ok("https://lucia.example.com/").base)
    }

    @Test fun `기본 포트는 지운다`() {
        assertEquals("https://lucia.example.com", ok("https://lucia.example.com:443").base)
        assertEquals("https://lucia.example.com:8443", ok("lucia.example.com:8443").base)
    }

    @Test fun `집 밖 주소에 평문은 받지 않는다`() {
        rejected("http://lucia.example.com")
        rejected("ws://8.8.8.8:8790")
    }

    @Test fun `집 안으로 보는 주소들`() {
        for (host in listOf("10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.0.1", "127.0.0.1", "169.254.1.1",
            "100.64.0.1", "100.127.255.254", "localhost", "nas.local", "hub.tail1234.ts.net", "[::1]", "[fd12:3456::1]", "[fe80::1]")) {
            assertTrue("$host 는 집 안", AddressPolicy.isHomeHost(host))
        }
    }

    @Test fun `집 안이 아닌 주소들`() {
        for (host in listOf("172.32.0.1", "100.128.0.1", "192.169.0.1", "8.8.8.8", "example.com", "11.0.0.1", "[2001:db8::1]")) {
            assertFalse("$host 는 집 밖", AddressPolicy.isHomeHost(host))
        }
    }

    @Test fun `이상한 입력`() {
        rejected("")
        rejected("   ")
        rejected("ftp://10.0.0.1")
        rejected("a b")
    }

    @Test fun `IPv6는 대괄호를 유지한다`() {
        assertEquals("http://[fd12:3456::1]:8790", ok("[fd12:3456::1]:8790").base)
    }
}
