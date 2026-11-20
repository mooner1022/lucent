package dev.mooner.lucent.ui.sky

import kotlin.math.floor

/**
 * 하늘 머리말의 장면. 기기의 지금 시각이 정한다(docs/design.md 6장).
 *
 * 경계는 해 뜨고 지는 때를 어림한 고정 시각이다. 위치를 묻지 않으려고 계절에 따라 옮기지 않는다.
 */
enum class SkyPhase {
    DAWN, DAY, DUSK, NIGHT;

    companion object {
        private const val DAWN_FROM = 5 * 60
        private const val DAY_FROM = 8 * 60
        private const val DUSK_FROM = 17 * 60 + 30
        private const val NIGHT_FROM = 20 * 60

        /** [minuteOfDay]는 자정부터 센 분(0..1439). 범위를 벗어난 값은 하루로 접는다. */
        fun at(minuteOfDay: Int): SkyPhase {
            val m = ((minuteOfDay % 1440) + 1440) % 1440
            return when {
                m < DAWN_FROM -> NIGHT
                m < DAY_FROM -> DAWN
                m < DUSK_FROM -> DAY
                m < NIGHT_FROM -> DUSK
                else -> NIGHT
            }
        }
    }
}

/** 루시아의 지금 상태가 화면에 주는 기색. 하늘과 아바타 고리가 이 값을 함께 본다. */
enum class Mood {
    /** 연결돼 있고 아무 일도 없다. */
    CALM,
    /** 루시아가 답을 쓰거나 일을 하는 중이다. */
    ACTIVE,
    /** 루시아가 내 답(승인)을 기다린다. */
    ATTENTION,
    /** 연결하는 중이다. */
    CONNECTING,
    /** 연결이 없다(끊김, 막힘). */
    OFFLINE,
}

/** 달의 위상. 머리말의 달은 오늘의 실제 달 모양이다. */
object Moon {
    /** 삭망월(일). */
    private const val SYNODIC_DAYS = 29.530588853
    /** 기준 삭: 2000-01-06 18:14 UTC. */
    private const val NEW_MOON_EPOCH_MS = 947_182_440_000L
    private const val DAY_MS = 86_400_000.0

    /** 0은 삭, 0.25는 상현, 0.5는 보름, 0.75는 하현. */
    fun phase(epochMs: Long): Float {
        val cycles = (epochMs - NEW_MOON_EPOCH_MS) / DAY_MS / SYNODIC_DAYS
        return (cycles - floor(cycles)).toFloat()
    }
}
