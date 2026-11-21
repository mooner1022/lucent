package dev.mooner.lucent.protocol

/** 입력 칸 하나의 값(`press.inputs`). 글은 다듬지 않고, 켜고 끄는 칸은 불로 보낸다. */
sealed interface FormValue {
    class Text(val value: String) : FormValue
    class Check(val on: Boolean) : FormValue
}

/**
 * 여러 칸을 받는 버튼(`form`, 사양 9.1·12.5, 결정 0063)을 다루는 규칙. 화면은 이 규칙만 따른다.
 *
 * 값(특히 비밀번호)은 `press.inputs`로 보내는 것 말고 어디에도 두지 않는다. 이 객체도 값을 쥐지 않는다.
 */
object Forms {
    /** 데몬이 받는 한도(5.6). 넘으면 `bad_frame`이다. */
    const val MAX_FIELDS = 8
    const val MAX_KEY_CHARS = 40
    const val MAX_VALUE_CHARS = 1000

    private val KINDS = setOf(FormField.TEXT, FormField.SECRET, FormField.CHECK)

    /**
     * 이 입력창을 그려서 보낼 수 있는가. 모르는 종류의 칸이 하나라도 있거나, 데몬이 받지 않을 모양(칸이 없다, 너무 많다,
     * 칸 이름이 비었거나 길거나 겹친다)이면 그리지 않는다.
     */
    fun drawable(form: CardForm): Boolean =
        form.fields.isNotEmpty() && form.fields.size <= MAX_FIELDS &&
            form.fields.all { it.kind in KINDS && it.key.isNotEmpty() && chars(it.key) <= MAX_KEY_CHARS } &&
            form.fields.map { it.key }.distinct().size == form.fields.size

    /**
     * 카드에 그릴 버튼. 입력창이 있는 버튼은 데몬이 `form`을 쓰고([forms], `welcome.capabilities.forms`) 앱이 그릴 수 있을
     * 때만 남긴다. 아니면 뺀다: 보통 버튼으로 그리면 눌러도 아무 일이 없고, 카드 본문에 같은 일을 하는 링크가 있다.
     */
    fun buttons(card: Card, forms: Boolean): List<CardButton> =
        card.buttons.filter { it.form == null || (forms && drawable(it.form)) }

    /** 글 칸의 길이 범위(자). 데몬의 값 한도를 넘지 않게 맞춘다. */
    fun range(field: FormField): IntRange {
        val max = field.maxLen.coerceIn(1, MAX_VALUE_CHARS)
        return field.minLen.coerceIn(0, max)..max
    }

    /** 넣은 글이 그 칸에 맞는가. 길이는 데몬과 같이 유니코드 문자 수로 센다. 글은 다듬지 않는다. */
    fun check(field: FormField, value: String): Problem? {
        val range = range(field)
        val length = chars(value)
        return when {
            length < range.first -> Problem.TooShort(range.first)
            length > range.last -> Problem.TooLong(range.last)
            else -> null
        }
    }

    sealed interface Problem {
        data class TooShort(val min: Int) : Problem
        data class TooLong(val max: Int) : Problem
    }

    private fun chars(text: String) = text.codePointCount(0, text.length)
}
