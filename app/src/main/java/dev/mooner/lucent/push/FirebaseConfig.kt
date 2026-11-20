package dev.mooner.lucent.push

import dev.mooner.lucent.protocol.LucentJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 앱이 푸시(FCM)를 쓰는 데 필요한 Firebase 값. 설정 화면에서 넣는다(docs/push.md).
 *
 * `google-services.json`에 든 값이고 비밀은 아니다(어느 앱이든 꾸러미에 그대로 실린다). 그래도 사용자의 프로젝트를
 * 가리키는 값이라 저장소·로그에는 넣지 않고 기기의 설정에만 둔다.
 *
 * 보내는 쪽 번호([senderId])는 프로젝트 번호이고 앱 ID의 둘째 마디와 같다. 직접 넣을 때는 앱 ID에서 읽는다.
 */
data class FirebaseConfig(
    val projectId: String,
    val appId: String,
    val apiKey: String,
    val senderId: String,
) {
    /** 설정 화면의 칸 셋. */
    enum class Field { PROJECT_ID, APP_ID, API_KEY }

    /** 직접 넣은 값을 읽은 결과. */
    sealed interface Entered {
        /** 세 칸이 모두 비었다: 푸시를 쓰지 않는다. */
        data object None : Entered
        data class Ok(val config: FirebaseConfig) : Entered
        /** 고쳐야 할 칸들. 일부만 채운 것도 여기다. */
        data class Invalid(val fields: Set<Field>) : Entered
    }

    /** `google-services.json`을 읽은 결과. */
    sealed interface Imported {
        data class Ok(val config: FirebaseConfig) : Imported
        /** 파일은 맞지만 이 앱(패키지 이름)의 설정이 없다. [packages]는 파일에 든 앱들이다. */
        data class NoClient(val packages: List<String>) : Imported
        /** `google-services.json`이 아니거나 값이 빠졌다. */
        data object NotConfig : Imported
    }

    companion object {
        /** 읽을 파일의 크기 한도. 실제 파일은 1 KB 안팎이다. */
        const val MAX_FILE_BYTES = 256 * 1024

        /** 안드로이드 앱 ID의 꼴: `1:<프로젝트 번호>:android:<16진수>`. */
        private val APP_ID = Regex("^\\d+:(\\d+):android:[0-9a-fA-F]+$")
        private val PROJECT_ID = Regex("^[a-z0-9][a-z0-9-]{2,}$")
        private val API_KEY = Regex("^[A-Za-z0-9_-]{20,}$")

        /** 화면의 세 칸에 넣은 글에서 설정을 만든다. 앞뒤 빈칸은 버린다. */
        fun entered(projectId: String, appId: String, apiKey: String): Entered {
            val project = projectId.trim()
            val app = appId.trim()
            val key = apiKey.trim()
            if (project.isEmpty() && app.isEmpty() && key.isEmpty()) return Entered.None
            val sender = APP_ID.matchEntire(app)?.groupValues?.get(1)
            val bad = buildSet {
                if (!PROJECT_ID.matches(project)) add(Field.PROJECT_ID)
                if (sender == null) add(Field.APP_ID)
                if (!API_KEY.matches(key)) add(Field.API_KEY)
            }
            if (bad.isNotEmpty() || sender == null) return Entered.Invalid(bad)
            return Entered.Ok(FirebaseConfig(project, app, key, sender))
        }

        /**
         * `google-services.json`의 글에서 [packageName] 앱의 설정을 찾는다. 빌드 종류마다 패키지 이름이 다르므로
         * (개발 빌드는 `.debug`가 붙는다) 그 이름으로 Firebase에 등록한 앱이 파일에 있어야 한다.
         */
        fun fromJson(text: String, packageName: String): Imported {
            val root = runCatching { LucentJson.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return Imported.NotConfig
            val info = root["project_info"] as? JsonObject ?: return Imported.NotConfig
            val clients = (root["client"] as? JsonArray)?.filterIsInstance<JsonObject>() ?: return Imported.NotConfig
            fun packageOf(client: JsonObject) =
                ((client["client_info"] as? JsonObject)?.get("android_client_info") as? JsonObject)?.get("package_name").text()
            val client = clients.firstOrNull { packageOf(it) == packageName }
                ?: return Imported.NoClient(clients.mapNotNull(::packageOf))
            val project = info["project_id"].text()
            val number = info["project_number"].text()
            val app = (client["client_info"] as? JsonObject)?.get("mobilesdk_app_id").text()
            val key = (client["api_key"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstNotNullOfOrNull { it["current_key"].text() }
            if (project == null || app == null || key == null) return Imported.NotConfig
            // 프로젝트 번호가 따로 적혀 있으면 그것을 쓰고, 없으면 앱 ID에서 읽는다.
            val sender = number ?: APP_ID.matchEntire(app)?.groupValues?.get(1) ?: return Imported.NotConfig
            return Imported.Ok(FirebaseConfig(project, app, key, sender))
        }

        private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }
}
