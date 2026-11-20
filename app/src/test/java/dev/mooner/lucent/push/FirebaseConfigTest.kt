package dev.mooner.lucent.push

import dev.mooner.lucent.push.FirebaseConfig.Entered
import dev.mooner.lucent.push.FirebaseConfig.Field
import dev.mooner.lucent.push.FirebaseConfig.Imported
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 값은 모두 지어낸 것이다(실제 프로젝트의 값을 시험에 넣지 않는다). */
class FirebaseConfigTest {

    private val key = "made-up-key-for-tests-0123456789abcdef"
    private val app = "1:123456789012:android:0a1b2c3d4e5f6a7b"

    private fun file(vararg packages: String, number: Boolean = true) = """
        {
          "project_info": {${if (number) "\"project_number\": \"123456789012\"," else ""} "project_id": "lucent-sample", "storage_bucket": "x"},
          "client": [${packages.joinToString(",") { client(it) }}],
          "configuration_version": "1"
        }
    """.trimIndent()

    private fun client(pkg: String) = """
        {"client_info": {"mobilesdk_app_id": "$app", "android_client_info": {"package_name": "$pkg"}},
         "oauth_client": [], "api_key": [{"current_key": "$key"}], "services": {}}
    """.trimIndent()

    @Test fun `세 칸이 모두 비면 푸시를 쓰지 않는다`() {
        assertEquals(Entered.None, FirebaseConfig.entered("", "  ", "\n"))
    }

    @Test fun `넣은 값에서 설정을 만들고 보내는 쪽 번호는 앱 ID에서 읽는다`() {
        val ok = FirebaseConfig.entered(" lucent-sample ", "$app\n", key) as Entered.Ok
        assertEquals(FirebaseConfig("lucent-sample", app, key, "123456789012"), ok.config)
    }

    @Test fun `일부만 채웠거나 꼴이 틀리면 그 칸을 알려 준다`() {
        assertEquals(Entered.Invalid(setOf(Field.APP_ID, Field.API_KEY)), FirebaseConfig.entered("lucent-sample", "", ""))
        assertEquals(Entered.Invalid(setOf(Field.PROJECT_ID)), FirebaseConfig.entered("Lucent Sample", app, key))
        // 웹 앱의 ID나 프로젝트 번호만 넣은 것은 안드로이드 앱 ID가 아니다.
        assertEquals(Entered.Invalid(setOf(Field.APP_ID)), FirebaseConfig.entered("lucent-sample", "1:123456789012:web:0a1b2c", key))
        assertEquals(Entered.Invalid(setOf(Field.APP_ID)), FirebaseConfig.entered("lucent-sample", "123456789012", key))
        assertEquals(Entered.Invalid(setOf(Field.API_KEY)), FirebaseConfig.entered("lucent-sample", app, "키 아님"))
    }

    @Test fun `파일에서 이 앱의 설정을 읽는다`() {
        val ok = FirebaseConfig.fromJson(file("dev.mooner.other", "dev.mooner.lucent"), "dev.mooner.lucent") as Imported.Ok
        assertEquals(FirebaseConfig("lucent-sample", app, key, "123456789012"), ok.config)
        // 읽은 값은 직접 넣은 것으로도 그대로 받아진다.
        assertEquals(Entered.Ok(ok.config), FirebaseConfig.entered(ok.config.projectId, ok.config.appId, ok.config.apiKey))
    }

    @Test fun `프로젝트 번호가 없는 파일은 앱 ID에서 읽는다`() {
        val ok = FirebaseConfig.fromJson(file("dev.mooner.lucent", number = false), "dev.mooner.lucent") as Imported.Ok
        assertEquals("123456789012", ok.config.senderId)
    }

    @Test fun `이 앱의 설정이 없으면 파일에 든 앱들을 알려 준다`() {
        val none = FirebaseConfig.fromJson(file("dev.mooner.lucent"), "dev.mooner.lucent.debug") as Imported.NoClient
        assertEquals(listOf("dev.mooner.lucent"), none.packages)
    }

    @Test fun `다른 파일은 받지 않는다`() {
        for (bad in listOf("", "not json", "[]", "{}", """{"project_info": 1, "client": []}""", """{"project_info": {}, "client": {}}""")) {
            assertEquals(bad, Imported.NotConfig, FirebaseConfig.fromJson(bad, "dev.mooner.lucent"))
        }
        // 값이 빠진 것도 받지 않는다.
        val noKey = file("dev.mooner.lucent").replace("\"current_key\": \"$key\"", "\"other\": 1")
        assertTrue(FirebaseConfig.fromJson(noKey, "dev.mooner.lucent") is Imported.NotConfig)
    }
}
