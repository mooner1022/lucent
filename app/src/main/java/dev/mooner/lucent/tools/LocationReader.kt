package dev.mooner.lucent.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import dev.mooner.lucent.protocol.ErrorCodes
import dev.mooner.lucent.protocol.InvokeReply
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * `location.get`: 휴대폰의 지금 위치. 플랫폼의 `LocationManager`로 한 번 잡는다(Google Play 서비스가 없는 기기도 있다).
 *
 * 위치 권한은 "앱을 쓰는 동안"만 받는다. 그래서 앱이 화면에 없으면(뒤로 간 뒤 연결이 닫히기 전, 푸시로 잠깐 깨어난 때)
 * 위치를 읽지 않고 그렇게 답한다.
 */
class LocationReader(private val context: Context, private val isForeground: () -> Boolean) {

    suspend fun read(precise: Boolean): InvokeReply {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = fine || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!coarse) return InvokeReply.Failed(ErrorCodes.DENIED, NO_PERMISSION)
        // 안드로이드 10부터 "앱을 쓰는 동안" 권한은 앱이 화면에 있을 때만 위치를 준다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isForeground()) return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NOT_ON_SCREEN)

        val manager = context.getSystemService(LocationManager::class.java) ?: return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NO_LOCATION)
        if (!LocationManagerCompat.isLocationEnabled(manager)) return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, LOCATION_OFF)

        val providers = providers(manager, precise && fine, fine)
        if (providers.isEmpty()) return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NO_LOCATION)

        val fresh = withTimeoutOrNull(FIX_TIMEOUT_MS) { current(manager, providers.first()) }
        val location = fresh ?: lastKnown(manager, providers) ?: return InvokeReply.Failed(ErrorCodes.UNAVAILABLE, NO_FIX)
        return InvokeReply.Ok(result(location, precise = fine && (precise || location.accuracy <= PRECISE_METERS)))
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** 해 볼 위치 공급자의 차례. 정밀하면 GPS가 먼저, 아니면 망(빠르고 전지를 덜 쓴다). */
    private fun providers(manager: LocationManager, precise: Boolean, fine: Boolean): List<String> {
        val enabled = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val order = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            if (precise) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            // 대략적인 권한만 있으면 안드로이드 12 전에는 GPS를 부를 수 없다.
            if (!precise && (fine || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)) add(LocationManager.GPS_PROVIDER)
        }
        return order.distinct().filter { it in enabled }
    }

    @SuppressLint("MissingPermission") // 위에서 권한을 확인했다.
    private suspend fun current(manager: LocationManager, provider: String): Location? = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        try {
            LocationManagerCompat.getCurrentLocation(manager, provider, cancel, DIRECT) { location ->
                if (cont.isActive) cont.resume(location)
            }
        } catch (e: SecurityException) {
            if (cont.isActive) cont.resume(null)
        } catch (e: IllegalArgumentException) {
            if (cont.isActive) cont.resume(null)
        }
    }

    /** 지금 위치를 잡지 못했으면 기기가 기억하는 마지막 위치(너무 오래되지 않은 것). */
    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager, providers: List<String>): Location? =
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { ageMillis(it) <= LAST_KNOWN_MAX_AGE_MS }
            .minByOrNull { ageMillis(it) }

    private fun ageMillis(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000

    private fun result(location: Location, precise: Boolean) = buildJsonObject {
        put("lat", round(location.latitude))
        put("lng", round(location.longitude))
        if (location.hasAccuracy()) put("accuracy_m", location.accuracy.roundToInt())
        val age = ageMillis(location)
        put("at", NoticeResults.time(System.currentTimeMillis() - age, ZoneId.systemDefault()))
        put("age_s", age / 1000)
        put("precise", precise)
    }

    /** 소수 다섯째 자리(1 m 남짓)면 충분하다. */
    private fun round(value: Double) = Math.round(value * 100_000) / 100_000.0

    companion object {
        private const val FIX_TIMEOUT_MS = 20_000L
        private const val LAST_KNOWN_MAX_AGE_MS = 15 * 60_000L
        /** 이보다 정확하면 정밀 위치로 본다. */
        private const val PRECISE_METERS = 100f
        private val DIRECT = Executor { it.run() }

        // 루시아가 사용자에게 그대로 전하는 까닭.
        const val NO_PERMISSION = "휴대폰에서 Lucent의 위치 권한이 꺼져 있다. 사용자가 Lucent 설정의 '휴대폰 기능'에서 위치를 허용하면 된다."
        const val NOT_ON_SCREEN = "Lucent가 화면에 열려 있을 때만 위치를 읽을 수 있다. 사용자가 Lucent를 열고 다시 물으면 된다."
        const val LOCATION_OFF = "휴대폰의 위치 기능이 꺼져 있다."
        const val NO_LOCATION = "이 휴대폰에서 쓸 수 있는 위치 공급자가 없다."
        const val NO_FIX = "위치를 잡지 못했다(실내이거나 신호가 약할 수 있다)."
    }
}
