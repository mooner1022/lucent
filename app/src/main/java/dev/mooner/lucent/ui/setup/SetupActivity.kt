package dev.mooner.lucent.ui.setup

import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.mooner.lucent.LucentHub
import dev.mooner.lucent.R
import dev.mooner.lucent.data.Route
import dev.mooner.lucent.databinding.ActivitySetupBinding
import dev.mooner.lucent.net.AddressPolicy
import dev.mooner.lucent.net.ApiException
import dev.mooner.lucent.net.GatewayClient
import dev.mooner.lucent.protocol.PROTOCOL_VERSION
import dev.mooner.lucent.protocol.Welcome
import dev.mooner.lucent.protocol.describeError
import dev.mooner.lucent.push.FirebaseConfig
import dev.mooner.lucent.push.PushRegistrar
import dev.mooner.lucent.ui.chat.ChatActivity
import dev.mooner.lucent.ui.common.applyPageInsets
import dev.mooner.lucent.ui.common.enableAppEdgeToEdge
import dev.mooner.lucent.ui.sky.PageSky
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 처음 설정 화면(처음 한 번, 그리고 바꿀 때). 앱을 쓰는 데 넣어야 하는 값은 모두 여기서 넣는다: 서버 주소, 토큰,
 * 그리고 푸시를 쓸 때의 Firebase 값.
 *
 * - 토큰은 관리 페이지 → 연결 → "앱·기기 연결"에서 발급한다(사양 3.1). 저장하기 전에 `GET /v1/me`로 두 주소를 각각
 *   확인한다. 집 안 주소는 밖에서 닿지 않는 것이 정상이므로, 한쪽만 닿아도 저장할 수 있다.
 * - Firebase 값은 선택이다(docs/push.md). `google-services.json`에서 불러오거나 직접 적는다. 비워 두면 푸시 없이 쓴다.
 *   빌드에 설정이 들어 있으면 그것을 쓰므로 칸을 보이지 않는다. 값이 맞는지는 여기서 확인할 수 없다(꼴만 본다):
 *   연결한 뒤 설정 → 알림 → 푸시가 등록 여부를 보인다.
 */
class SetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupBinding
    private lateinit var sky: PageSky
    private val hub by lazy { LucentHub.get(this) }
    private val editing by lazy { intent.getBooleanExtra(EXTRA_EDIT, false) && hub.settings.isConfigured }

    /** Firebase 값을 여기서 넣는가. 빌드에 설정이 들어 있으면 넣지 않는다. */
    private val pushEditable by lazy { hub.push.source != PushRegistrar.Source.BUILD }
    private val pickPushFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPushFile(uri)
    }

    /** 한 길의 확인 결과. */
    private sealed interface Probe {
        val route: Route
        class Ok(override val route: Route, val welcome: Welcome) : Probe
        class Rejected(override val route: Route, val reason: String) : Probe
        /** 서버가 인증을 거절했다. 토큰이 틀린 것이므로 저장하지 않는다. */
        class Unauthorized(override val route: Route, val reason: String) : Probe
        /** 서버에 닿지 못했다(집 밖에서 집 안 주소 등). */
        class Unreachable(override val route: Route, val reason: String) : Probe
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableAppEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyPageInsets(binding.root, binding.pageHeader.header, binding.pageHeader.headerContent, binding.pageHeader.sky, binding.scroll)
        sky = PageSky(this, binding.pageHeader)
        sky.refresh(animate = false)
        // 처음 연결할 때는 돌아갈 화면이 없다: 뒤로 가기 대신 루시아의 얼굴과 첫 줄을 보인다.
        binding.hero.isVisible = !editing
        binding.pageHeader.back.isVisible = editing
        if (!editing) binding.pageHeader.pageTitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = resources.getDimensionPixelSize(R.dimen.space_m)
        }

        binding.pageHeader.pageTitle.setText(if (editing) R.string.setup_title_edit else R.string.setup_title)
        binding.pageHeader.back.setOnClickListener { finish() }

        if (editing) {
            binding.home.setText(hub.settings.homeBase.orEmpty())
            binding.publicAddress.setText(hub.settings.publicBase.orEmpty())
            binding.tokenLayout.hint = getString(R.string.setup_token_keep_hint)
            binding.tokenHelp.setText(R.string.setup_token_help_edit)
        }

        binding.tokenLayout.setEndIconOnClickListener { pasteToken() }
        for (field in listOf(binding.home, binding.publicAddress, binding.token)) {
            field.doAfterTextChanged {
                binding.homeLayout.error = null
                binding.publicLayout.error = null
                binding.tokenLayout.error = null
            }
        }

        setUpPush(savedInstanceState)

        binding.check.setOnClickListener { run(save = false) }
        binding.save.setOnClickListener { run(save = true) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PUSH_FIELDS, binding.pushFields.isVisible)
    }

    override fun onResume() {
        super.onResume()
        sky.refresh()
    }

    private fun pasteToken() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, R.string.setup_clipboard_empty, Toast.LENGTH_SHORT).show()
        } else {
            binding.token.setText(text)
        }
    }

    // ------------------------------------------------------------------ 푸시(Firebase 값)

    private fun setUpPush(savedInstanceState: Bundle?) {
        if (!pushEditable) {
            binding.pushHelp.text = getString(R.string.setup_push_help_build, hub.push.buildProjectId.orEmpty())
            binding.pushActions.isVisible = false
            return
        }
        if (savedInstanceState == null) hub.settings.firebase?.let(::fillPush)
        else if (savedInstanceState.getBoolean(STATE_PUSH_FIELDS)) binding.pushFields.isVisible = true
        binding.pushImport.setOnClickListener { pickPushFile.launch(PUSH_FILE_TYPES) }
        binding.pushManual.setOnClickListener {
            binding.pushFields.isVisible = true
            binding.projectId.requestFocus()
            WindowCompat.getInsetsController(window, binding.projectId).show(WindowInsetsCompat.Type.ime())
        }
        for (field in listOf(binding.projectId, binding.appId, binding.apiKey)) {
            field.doAfterTextChanged {
                binding.projectIdLayout.error = null
                binding.appIdLayout.error = null
                binding.apiKeyLayout.error = null
            }
        }
        // 설정 화면의 푸시 줄에서 왔으면 이 구역부터 보인다.
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_PUSH, false)) {
            binding.scroll.post { binding.scroll.scrollTo(0, binding.pushTitle.top) }
        }
    }

    private fun fillPush(config: FirebaseConfig) {
        binding.pushFields.isVisible = true
        binding.projectId.setText(config.projectId)
        binding.appId.setText(config.appId)
        binding.apiKey.setText(config.apiKey)
    }

    /** 고른 `google-services.json`에서 이 앱의 값을 읽어 칸에 채운다. 저장은 아래의 단추가 한다. */
    private fun importPushFile(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { readCapped(uri) }.getOrNull() }
            when (val read = text?.let { FirebaseConfig.fromJson(it, packageName) }) {
                null -> pushNote(getString(R.string.setup_push_unreadable), failed = true)
                is FirebaseConfig.Imported.Ok -> {
                    fillPush(read.config)
                    pushNote(getString(R.string.setup_push_imported, read.config.projectId), failed = false)
                }
                is FirebaseConfig.Imported.NoClient -> pushNote(
                    if (read.packages.isEmpty()) getString(R.string.setup_push_no_client, packageName)
                    else getString(R.string.setup_push_no_client_has, packageName, read.packages.joinToString(", ")),
                    failed = true,
                )
                FirebaseConfig.Imported.NotConfig -> pushNote(getString(R.string.setup_push_not_config), failed = true)
            }
        }
    }

    /** 파일의 글. 한도를 넘으면 설정 파일이 아니므로 읽지 않는다(null). */
    private fun readCapped(uri: Uri): String? = contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > FirebaseConfig.MAX_FILE_BYTES) return@use null
        }
        out.toString(Charsets.UTF_8.name())
    }

    private fun pushNote(text: String, failed: Boolean) {
        binding.pushNote.text = text
        binding.pushNote.setTextColor(getColor(if (failed) R.color.error else R.color.ink_muted))
        binding.pushNote.isVisible = true
    }

    /** 칸에 넣은 Firebase 값. 고쳐야 할 칸이 있으면 그 칸에 까닭을 적고 null을 돌려준다. */
    private fun readPush(): FirebaseConfig.Entered? {
        if (!pushEditable) return FirebaseConfig.Entered.None
        val entered = FirebaseConfig.entered(binding.projectId.text.toString(), binding.appId.text.toString(), binding.apiKey.text.toString())
        if (entered !is FirebaseConfig.Entered.Invalid) return entered
        binding.pushFields.isVisible = true
        val layouts = mapOf(
            FirebaseConfig.Field.PROJECT_ID to (binding.projectIdLayout to R.string.setup_push_project_invalid),
            FirebaseConfig.Field.APP_ID to (binding.appIdLayout to R.string.setup_push_app_invalid),
            FirebaseConfig.Field.API_KEY to (binding.apiKeyLayout to R.string.setup_push_key_invalid),
        )
        for (field in entered.fields) layouts.getValue(field).let { (layout, reason) -> layout.error = getString(reason) }
        layouts.getValue(entered.fields.first()).first.requestFocus()
        return null
    }

    // ------------------------------------------------------------------ 확인과 저장

    /** 입력을 검사하고 두 주소를 확인한다. [save]이면 쓸 수 있을 때 저장하고 대화 화면으로 간다. */
    private fun run(save: Boolean) {
        val home = parse(binding.home.text.toString(), binding.homeLayout)
        val pub = parse(binding.publicAddress.text.toString(), binding.publicLayout)
        if (home === INVALID || pub === INVALID) return

        val addresses = listOfNotNull(
            (home as? AddressPolicy.Result.Ok)?.let { Route.HOME to it },
            (pub as? AddressPolicy.Result.Ok)?.let { Route.PUBLIC to it },
        )
        if (addresses.isEmpty()) {
            binding.homeLayout.error = getString(R.string.setup_need_address)
            return
        }

        val typed = binding.token.text.toString().trim()
        val token = typed.ifEmpty { if (editing) hub.settings.token.orEmpty() else "" }
        if (!tokenLooksValid(token)) {
            binding.tokenLayout.error = getString(R.string.setup_token_invalid)
            return
        }
        val push = (readPush() ?: return).let { (it as? FirebaseConfig.Entered.Ok)?.config }

        setBusy(true)
        lifecycleScope.launch {
            val probes = addresses.map { (route, ok) -> async { probe(route, ok.base, token) } }.awaitAll()
            setBusy(false)
            showResults(probes)
            if (save) finishSave(probes, addresses.associate { it.first to it.second.base }, token, push)
        }
    }

    private fun parse(text: String, layout: com.google.android.material.textfield.TextInputLayout): Any? {
        if (text.isBlank()) return null
        return when (val r = AddressPolicy.parse(text)) {
            is AddressPolicy.Result.Ok -> r
            is AddressPolicy.Result.Rejected -> {
                layout.error = r.reason
                INVALID
            }
        }
    }

    private fun tokenLooksValid(token: String) =
        token.startsWith("lucia_") && token.length >= 20 && token.none { it.isWhitespace() }

    private suspend fun probe(route: Route, base: String, token: String): Probe = try {
        val welcome = hub.api.probe(base, token)
        when {
            welcome.role != "app" -> Probe.Rejected(route, getString(R.string.setup_wrong_role))
            else -> Probe.Ok(route, welcome)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ApiException) {
        if (e.status == 401 || e.status == 429) Probe.Unauthorized(route, describeError(e.code, e.message.orEmpty()))
        else Probe.Rejected(route, describeError(e.code, e.message.orEmpty()))
    } catch (e: Exception) {
        Probe.Unreachable(route, GatewayClient.describeFailure(e))
    }

    private fun finishSave(probes: List<Probe>, bases: Map<Route, String>, token: String, push: FirebaseConfig?) {
        val ok = probes.filterIsInstance<Probe.Ok>()
        when {
            probes.any { it is Probe.Unauthorized } -> return // 결과 줄이 이유를 보인다.
            ok.isNotEmpty() -> commit(bases, token, ok.first().route, push)
            probes.all { it is Probe.Unreachable } -> {
                // 집 안 주소만 넣고 밖에서 설정하는 경우처럼, 지금은 닿지 않아도 저장하고 싶을 수 있다.
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.setup_unreachable_title)
                    .setMessage(R.string.setup_unreachable_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.setup_save_anyway) { _, _ -> commit(bases, token, bases.keys.first(), push) }
                    .show()
            }
            else -> Unit
        }
    }

    private fun commit(bases: Map<Route, String>, token: String, preferred: Route, push: FirebaseConfig?) {
        hub.settings.homeBase = bases[Route.HOME]
        hub.settings.publicBase = bases[Route.PUBLIC]
        hub.settings.token = token
        hub.settings.lastRoute = preferred
        if (pushEditable) hub.settings.firebase = push
        if (!hub.pushConfigChanged()) {
            restartApp()
            return
        }
        hub.reconnect()
        startActivity(Intent(this, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    /**
     * 이 프로세스는 예전 Firebase 값으로 켜져 있다. 켜 둔 것을 갈아 끼우지 않고, 앱을 새로 시작해 저장한 값으로 켠다
     * (시작하면 새 값으로 연결하고 푸시를 등록한다).
     */
    private fun restartApp() {
        hub.settings.flush()
        startActivity(Intent.makeRestartActivityTask(ComponentName(this, ChatActivity::class.java)))
        Runtime.getRuntime().exit(0)
    }

    // ------------------------------------------------------------------ 결과 줄

    private fun showResults(probes: List<Probe>) {
        binding.results.removeAllViews()
        binding.results.isVisible = probes.isNotEmpty()
        val inflater = LayoutInflater.from(this)
        for (p in probes) {
            val row = inflater.inflate(R.layout.item_probe, binding.results, false) as TextView
            val label = getString(if (p.route == Route.HOME) R.string.route_home else R.string.route_public)
            val (text, color, icon) = when (p) {
                is Probe.Ok -> Triple(
                    getString(R.string.setup_result_ok, label, p.welcome.name.ifBlank { p.welcome.client }, scopeText(p.welcome), versionNote(p.welcome)),
                    R.color.ok, R.drawable.ic_check,
                )
                is Probe.Unauthorized -> Triple(getString(R.string.setup_result_fail, label, p.reason), R.color.error, R.drawable.ic_warning)
                is Probe.Rejected -> Triple(getString(R.string.setup_result_fail, label, p.reason), R.color.error, R.drawable.ic_warning)
                is Probe.Unreachable -> Triple(getString(R.string.setup_result_fail, label, p.reason), R.color.warn, R.drawable.ic_warning)
            }
            row.text = text
            row.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            row.compoundDrawableTintList = android.content.res.ColorStateList.valueOf(getColor(color))
            binding.results.addView(row)
        }
    }

    private fun scopeText(w: Welcome) =
        if (w.scopes.isEmpty()) getString(R.string.scopes_none) else w.scopes.joinToString(", ")

    /** 서버가 앱보다 새 판이면 앱 업데이트를 권한다(5.3). */
    private fun versionNote(w: Welcome) =
        if (w.v > PROTOCOL_VERSION) getString(R.string.setup_newer_protocol) else ""

    private fun setBusy(busy: Boolean) {
        binding.check.isEnabled = !busy
        binding.save.isEnabled = !busy
        binding.check.alpha = if (busy) BUSY_ALPHA else 1f
        binding.save.alpha = if (busy) BUSY_ALPHA else 1f
        binding.check.setText(if (busy) R.string.setup_checking else R.string.setup_check)
    }

    companion object {
        const val EXTRA_EDIT = "edit"
        /** 푸시 구역부터 보인다(설정 화면의 푸시 줄에서 올 때). */
        const val EXTRA_PUSH = "push"
        private const val STATE_PUSH_FIELDS = "push_fields"
        /** `.json`을 다른 종류로 알려 주는 파일 앱이 있어 넓게 받는다. 내용은 읽어서 가린다. */
        private val PUSH_FILE_TYPES = arrayOf("application/json", "text/*", "application/octet-stream")
        private val INVALID = Any()
        private const val BUSY_ALPHA = 0.5f
    }
}
