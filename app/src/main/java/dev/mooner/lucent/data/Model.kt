package dev.mooner.lucent.data

import dev.mooner.lucent.protocol.Card
import dev.mooner.lucent.protocol.FileRef

/** 말한 쪽. `DAEMON`은 데몬이 남긴 기록(브리핑 등, 사양 6.2)이다. */
enum class Sender {
    USER, LUCIA, DAEMON;

    companion object {
        fun fromHistory(from: String) = when (from) {
            "user" -> USER
            "daemon" -> DAEMON
            else -> LUCIA
        }
    }
}

enum class Kind { TEXT, NOTE, FILES, CARD }

/** 앱에서 보낸 말의 상태. 데몬은 같은 말을 거르지 않으므로(12.3) 확인되지 않은 말은 사용자가 다시 보낼지 정한다. */
enum class Delivery {
    SENT,
    /** `message`를 보냈고 `accepted`를 기다린다. */
    SENDING,
    /** `accepted`가 오기 전에 연결이 끊겼다. 데몬이 받았는지 알 수 없다. */
    UNCONFIRMED,
    /** 서버가 오류로 거절했거나 보내지 못했다. 데몬은 받지 않았으므로 다시 보내도 겹치지 않는다. */
    FAILED,
}

/** 화면에 그릴 한 건. 서버 프레임, 보낸 말, 가져온 기록이 모두 이 모양으로 저장된다. */
data class StoredItem(
    val id: Long,
    val key: String,
    /** 정렬과 표시에 쓰는 시각(epoch ms). 번호 붙는 프레임에는 시각이 없어 받은 때를 쓴다. */
    val at: Long,
    val sender: Sender,
    val kind: Kind,
    val body: String,
    val ref: String? = null,
    val card: Card? = null,
    val files: List<FileRef> = emptyList(),
    val seq: Long = 0,
    val delivery: Delivery = Delivery.SENT,
    val localId: String? = null,
    /** 가져온 기록의 대화 ID. 이 앱의 대화가 아니면(Discord 등) 출처 표시를 단다. */
    val conversation: String? = null,
    /**
     * 이 말에 붙은 스티커(app-expressions.md 5장). 스티커만 있는 줄은 [body]가 비어 있다: 루시아의 `sticker` 프레임과
     * 글 없이 보낸 내 스티커가 그렇다. 글과 함께 보낸 것과 기록에서 읽은 것은 글과 스티커가 한 줄에 있다.
     */
    val sticker: StickerRef? = null,
)
