package dev.mooner.lucent.data

/** 명령의 답(사람이 읽는 글)에서 지금 값을 읽는다. 구조화된 값이 따로 없어서, 확실할 때만 읽는다. */
object CommandReply {
    /**
     * [options] 가운데 [text]가 가리키는 것. 굵게 쓴(`**기본**`) 선택지가 하나뿐이면 그것, 아니면 글에 이름이 나온 선택지가
     * 하나뿐일 때 그것이다. 둘 이상이거나 없으면 모른다(null). 모르면 화면은 어느 것에도 표시하지 않는다.
     */
    fun current(text: String?, options: List<String>): String? {
        if (text.isNullOrBlank()) return null
        val bold = options.filter { "**$it**" in text }
        if (bold.size == 1) return bold.single()
        if (bold.size > 1) return null
        return options.filter { it in text }.singleOrNull()
    }
}
