package dev.mooner.lucent.ui.common

import android.content.Context
import android.text.format.Formatter
import dev.mooner.lucent.R
import dev.mooner.lucent.protocol.FileRef
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Format {

    /** 앱의 글이 한국어 하나이므로 날짜·시각도 기기 언어와 상관없이 한국어로 보인다(영어 기기에서 섞여 보이지 않게). */
    private val locale: Locale = Locale.KOREAN
    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    private val dayFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)

    fun time(at: Long): String =
        Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(timeFormat)

    fun day(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dayFormat)

    private val shortDayFormat = DateTimeFormatter.ofPattern("M월 d일", locale)

    /** 지난 때를 짧게: 방금, 5분 전, 3시간 전, 어제를 넘으면 날짜와 시각(11월 18일 오후 3:10). */
    fun ago(at: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - at).coerceAtLeast(0) / 60_000
        return when {
            minutes < 1 -> "방금"
            minutes < 60 -> "${minutes}분 전"
            minutes < 24 * 60 -> "${minutes / 60}시간 전"
            else -> Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).let { "${it.format(shortDayFormat)} ${it.format(timeFormat)}" }
        }
    }

    fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun kind(context: Context, file: FileRef): String = context.getString(
        when (file.kind) {
            "image" -> R.string.file_kind_image
            "pdf" -> R.string.file_kind_pdf
            "text" -> R.string.file_kind_text
            "office" -> R.string.file_kind_office
            else -> R.string.file_kind_other
        },
    )

    /** 크기 · 종류. 기록에 보인 파일이 지워졌거나 이 기기에서 받을 수 없으면 종류 대신 그 까닭을 적는다(사양 6.2). */
    fun fileMeta(context: Context, file: FileRef): String {
        val second = when {
            file.gone -> context.getString(R.string.file_state_gone)
            !file.reachable -> context.getString(R.string.file_state_unreachable)
            else -> kind(context, file)
        }
        return context.getString(R.string.file_meta, size(context, file.size), second)
    }

    /** 몇 초 뒤 다시 시도한다는 안내용. */
    fun secondsUntil(at: Long): Int = ((at - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)

    fun zone(): ZoneId = ZoneId.systemDefault()
}
