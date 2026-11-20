package dev.mooner.lucent.ui.common

/**
 * 서버가 카드·알림·명령 글에 붙여 보내는 이모지를 걷어 낸다.
 *
 * Discord에서 오는 글은 `✅ 보냈습니다`, `⌛ 답이 없어 만료됐습니다`, `🛡️ 권한 모드`처럼 이모지로 어조를 전한다. 앱은 같은 뜻을
 * 카드의 어조(`tone`)에 따른 아이콘과 색으로 이미 보이므로 이모지를 또 그리면 겹치고, 기기마다 모양이 달라 화면의 결도 깨진다.
 * 그래서 앱이 그리는 글(카드, 진행 안내, 명령 답)에서는 이모지를 지운다. 사용자와 루시아의 대화 글은 내용이므로 건드리지 않는다.
 */
object Plain {

    fun text(raw: String): String {
        if (raw.isEmpty()) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (!isDecorative(cp)) out.appendCodePoint(cp)
        }
        // 이모지가 있던 자리의 공백을 정리한다: 앞뒤 공백, 줄 안의 겹친 공백.
        return out.toString().lines().joinToString("\n") { it.replace(Regex("[ \\t]{2,}"), " ").trim() }.trim()
    }

    fun isDecorative(cp: Int): Boolean = when (cp) {
        0x200D, 0xFE0E, 0xFE0F, 0x20E3 -> true               // ZWJ, 변형 선택자, 키캡
        in 0x1F000..0x1FAFF -> true                           // 이모지·기호·국기·픽토그램
        in 0x2600..0x27BF -> true                             // 기타 기호, 딩뱃(✅ ❌ ✔ ✨ ⚠)
        0x231A, 0x231B -> true                                // ⌚ ⌛
        in 0x23E9..0x23F3, in 0x23F8..0x23FA -> true          // ⏩ ⏳ ⏸ 등
        in 0x2B00..0x2BFF -> true                             // ⭐ ⬆ 등
        0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139 -> true
        in 0x2194..0x21AA -> false                            // 화살표(↔ ↩ 등)는 글자로 쓴다
        else -> false
    }
}
