package dev.mooner.lucent.ui.settings

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dev.mooner.lucent.BuildConfig
import dev.mooner.lucent.LucentHub
import dev.mooner.lucent.LucentHub.Notice
import dev.mooner.lucent.R
import dev.mooner.lucent.data.Route
import dev.mooner.lucent.databinding.ActivitySettingsBinding
import dev.mooner.lucent.databinding.ItemSettingRowBinding
import dev.mooner.lucent.databinding.ViewSectionBinding
import dev.mooner.lucent.net.AddressPolicy
import dev.mooner.lucent.net.LinkState
import dev.mooner.lucent.protocol.PROTOCOL_VERSION
import dev.mooner.lucent.protocol.Welcome
import dev.mooner.lucent.tools.NotificationWatcher
import dev.mooner.lucent.tools.ToolSpecs
import dev.mooner.lucent.tools.ToolSwitch
import dev.mooner.lucent.ui.common.CommandSheet
import dev.mooner.lucent.ui.common.FileActions
import dev.mooner.lucent.ui.common.Format
import dev.mooner.lucent.ui.common.Peeks
import dev.mooner.lucent.ui.common.ReplyAskSheet
import dev.mooner.lucent.ui.common.applyPageInsets
import dev.mooner.lucent.ui.common.enableAppEdgeToEdge
import dev.mooner.lucent.ui.setup.SetupActivity
import dev.mooner.lucent.ui.sky.Mood
import dev.mooner.lucent.ui.sky.PageSky
import kotlinx.coroutines.launch

/**
 * 연결과 서버, 알림, 일하는 방식(권한 모드·주 모델), 휴대폰 기능, 기록, 앱 정보를 한곳에 모은 화면. 섹션은 StarLight 설정 화면처럼 흰 면 하나씩이다
 * (페이지 → 면 → 행, 두 겹). 머리말의 하늘은 연결이 없으면 안개가 낀다.
 *
 * 값이 바뀌면(연결 상태, 서버가 알려 준 정보) [render]가 섹션을 처음부터 다시 만든다.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var sky: PageSky
    private val hub by lazy { LucentHub.get(this) }

    /** `GET /v1/me`로 새로 읽은 값(번호, 푸시 등록). */
    private var server: Welcome? = null
    private var pushBusy = false

    /** "일하는 방식" 섹션(권한 모드, 주 모델 등): 명령 목록은 허브가 기억한다. 읽지 못했으면 그 이유. */
    private var commandsError: String? = null

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.none { it }) Peeks.show(this, getString(R.string.tool_location_denied), Notice.Level.WARNING)
        render()
    }

    /** 루시아가 알림에 답장하기 전의 물음(이 화면이 앞에 있을 때). */
    private val replyAsk by lazy { ReplyAskSheet(this, hub) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableAppEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyPageInsets(binding.root, binding.pageHeader.header, binding.pageHeader.headerContent, binding.pageHeader.sky, binding.scroll)
        sky = PageSky(this, binding.pageHeader)
        sky.refresh(moodOf(hub.link.value), animate = false)
        binding.pageHeader.pageTitle.setText(R.string.settings_title)
        binding.pageHeader.back.setOnClickListener { finish() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // 화면이 앞에 올 때마다 명령 목록을 다시 읽는다(지금 값은 다른 곳에서도 바뀐다).
                var loaded = false
                launch {
                    hub.link.collect {
                        if (it is LinkState.Online && !loaded) {
                            loaded = true
                            launch { loadCommands() }
                        }
                        sky.refresh(moodOf(it))
                        render()
                    }
                }
                launch { hub.welcome.collect { render() } }
                launch { hub.commandTexts.collect { render() } }
                launch { hub.commandList.collect { render() } }
                launch { hub.modelSwitch.collect { render() } }
                // 고르는 시트를 닫은 뒤에 끝난 일(대화를 정리하고 모델 바꾸기)의 결과.
                launch { hub.notices.collect { Peeks.show(this@SettingsActivity, it) } }
                launch { replyAsk.watch() }
                launch { loadServer() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sky.refresh()
        // 시스템 설정에서 알림 접근을 허용하고 돌아왔으면 알림 읽기를 붙인다.
        if (hub.settings.toolNotifications) NotificationWatcher.start(this)
        render() // 알림 권한을 시스템 설정에서 바꾸고 돌아온 경우
    }

    private fun moodOf(link: LinkState) = when (link) {
        is LinkState.Online -> Mood.CALM
        is LinkState.Connecting, LinkState.Idle -> Mood.CONNECTING
        else -> Mood.OFFLINE
    }

    // ------------------------------------------------------------------ 읽기

    private suspend fun loadServer() {
        server = runCatching { hub.api.me() }.getOrNull()
        render()
    }

    private suspend fun loadCommands() {
        val welcome = hub.welcome.value ?: return
        if (!welcome.canCall("commands") || !welcome.canCall("command")) return
        try {
            val commands = hub.commands()
            // 지금 값을 알려 주지 않는 예전 데몬이면 명령마다 물어 답의 글에서 읽는다.
            for (command in commands.filter { it.current == null }) hub.command(command.name, null)
            commandsError = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            commandsError = e.message ?: getString(R.string.command_load_failed)
        }
        render()
    }

    // ------------------------------------------------------------------ 그리기

    private fun render() {
        if (!::binding.isInitialized) return
        val sections = binding.sections
        sections.removeAllViews()
        val welcome = hub.welcome.value

        connectionSection(sections)
        serverSection(sections, welcome)
        notificationSection(sections, welcome)
        if (welcome?.canCall("command") == true) workSection(sections)
        toolsSection(sections, welcome)
        historySection(sections)
        aboutSection(sections)
        dangerSection(sections)
    }

    private fun connectionSection(parent: LinearLayout) {
        val rows = section(parent, R.string.settings_connection)
        val link = hub.link.value
        row(rows, R.string.settings_status, linkText(link))
        row(rows, R.string.settings_home_address, hub.settings.homeBase?.let(AddressPolicy::display) ?: getString(R.string.not_set), onClick = ::editConnection)
        row(rows, R.string.settings_public_address, hub.settings.publicBase?.let(AddressPolicy::display) ?: getString(R.string.not_set), onClick = ::editConnection)
        hub.welcome.value?.let { row(rows, R.string.settings_device_name, "${it.name.ifBlank { getString(R.string.unnamed) }} · ${it.client}") }
        row(rows, R.string.settings_edit_connection, getString(R.string.settings_edit_connection_value), onClick = ::editConnection)
    }

    private fun serverSection(parent: LinearLayout, welcome: Welcome?) {
        val rows = section(parent, R.string.settings_server)
        if (welcome == null) {
            row(rows, R.string.settings_server_unknown, getString(R.string.settings_server_unknown_value), chevron = false)
            return
        }
        row(rows, R.string.settings_protocol, getString(R.string.settings_protocol_value, welcome.v, PROTOCOL_VERSION))
        row(
            rows, R.string.settings_notify_target,
            getString(if (welcome.notifyTarget) R.string.notify_target_app else R.string.notify_target_discord),
        )
        row(rows, R.string.settings_scopes, if (welcome.scopes.isEmpty()) getString(R.string.scopes_none) else welcome.scopes.joinToString(", "))
        server?.let { row(rows, R.string.settings_box, getString(R.string.settings_box_value, it.seq, it.acked)) }
    }

    private fun notificationSection(parent: LinearLayout, welcome: Welcome?) {
        val rows = section(parent, R.string.settings_notifications, R.string.settings_notifications_hint)

        val granted = hub.notifier.canPost()
        row(rows, R.string.settings_permission, getString(if (granted) R.string.permission_granted else R.string.permission_off), onClick = { askPermission(granted) })

        val pushText = when {
            !hub.push.isSupported -> getString(R.string.push_unsupported_build)
            welcome != null && !welcome.push.available -> getString(R.string.push_server_unavailable)
            pushBusy -> getString(R.string.push_registering)
            (server?.push?.registered ?: welcome?.push?.registered) == true -> getString(R.string.push_registered)
            else -> getString(R.string.push_not_registered)
        }
        val canRegister = hub.push.isSupported && welcome?.push?.available == true && !pushBusy
        row(rows, R.string.settings_push, pushText, chevron = canRegister, onClick = if (canRegister) ({ registerPush() }) else null)

        switchRow(rows, R.string.settings_preview, R.string.settings_preview_hint, hub.settings.previewInNotifications) {
            hub.settings.previewInNotifications = it
        }
    }

    /**
     * 일하는 방식: 서버가 알려 준 명령(권한 모드, 주 모델 등)마다 지금 값 한 줄. 누르면 "더하기" 시트와 같은 고르는 시트가
     * 열린다([CommandSheet]). 명령이 늘면 줄도 늘어난다.
     */
    private fun workSection(parent: LinearLayout) {
        val rows = section(parent, R.string.settings_work, R.string.settings_work_hint)
        val commands = hub.commandList.value
        when {
            commands.isNotEmpty() -> for (command in commands) {
                row(rows, CommandSheet.title(this, command), CommandSheet.value(this, hub, command)) {
                    CommandSheet.show(this, hub, command) { render() }
                }
            }
            commandsError != null -> row(rows, R.string.command_load_failed, commandsError.orEmpty(), chevron = false)
            else -> row(rows, R.string.mode_loading, "", chevron = false)
        }
    }

    /**
     * 휴대폰 기능: 루시아에게 내주는 기능의 스위치(사양 7.2). 스위치는 "내줄지"이고, 휴대폰 권한은 따로 줄의 글로 알린다
     * (스위치를 켜면 권한을 묻는다). 루시아가 실제로 부르려면 관리 페이지에서 범위도 켜야 한다.
     */
    private fun toolsSection(parent: LinearLayout, welcome: Welcome?) {
        val rows = section(parent, R.string.settings_tools, R.string.settings_tools_hint)
        val settings = hub.settings
        if (welcome != null && !welcome.hasScope(ToolSpecs.SCOPE)) {
            row(rows, R.string.tools_scope_missing, getString(R.string.tools_scope_missing_value), chevron = false)
        }
        val offered = hub.tools.offeredNames()
        if (hub.link.value is LinkState.Online && welcome != null && offered.isNotEmpty() && offered == hub.sentToolNames && welcome.tools.isEmpty()) {
            row(rows, R.string.tools_unsupported, getString(R.string.tools_unsupported_value), chevron = false)
        }

        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = fine || granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        val locationNote = when {
            !settings.toolLocation -> getString(R.string.tool_location_hint)
            !coarse -> getString(R.string.tool_location_need)
            !fine -> getString(R.string.tool_location_coarse)
            else -> used(R.string.tool_location_hint, ToolSwitch.LOCATION)
        }
        toolRow(
            rows, R.string.tool_location, locationNote, settings.toolLocation,
            onRowClick = if (settings.toolLocation && !fine) ({ askLocation() }) else null,
        ) { on ->
            settings.toolLocation = on
            hub.toolsChanged()
            if (on && !coarse) askLocation()
        }

        val allowed = NotificationWatcher.isAllowed(this)
        val noticesNote = when {
            !settings.toolNotifications -> getString(R.string.tool_notifications_hint)
            !allowed -> getString(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) R.string.tool_notifications_need_restricted else R.string.tool_notifications_need,
            )
            else -> used(R.string.tool_notifications_hint, ToolSwitch.NOTIFICATIONS)
        }
        toolRow(
            rows, R.string.tool_notifications, noticesNote, settings.toolNotifications,
            onRowClick = if (settings.toolNotifications && !allowed) ({ openListenerSettings() }) else null,
        ) { on ->
            settings.toolNotifications = on
            if (on) {
                if (NotificationWatcher.isAllowed(this)) NotificationWatcher.start(this) else openListenerSettings()
            } else {
                // 끄면 기록을 남기지 않는다.
                NotificationWatcher.stop()
                hub.tools.clearNotices()
            }
            hub.toolsChanged()
        }

        val replyNote = when {
            !settings.toolNotifications -> getString(R.string.tool_reply_needs_log)
            settings.toolReply -> used(R.string.tool_reply_hint, ToolSwitch.REPLY)
            else -> getString(R.string.tool_reply_hint)
        }
        toolRow(rows, R.string.tool_reply, replyNote, settings.toolReply, enabled = settings.toolNotifications) { on ->
            settings.toolReply = on
            hub.toolsChanged()
        }
        if (settings.toolNotifications && settings.toolReply) {
            toolRow(rows, R.string.tool_reply_confirm, getString(R.string.tool_reply_confirm_hint), settings.replyConfirm) { on ->
                settings.replyConfirm = on
            }
        }
        if (settings.toolNotifications) {
            row(rows, R.string.tool_log_clear, getString(R.string.tool_log_count, hub.tools.noticeCount()), onClick = {
                hub.tools.clearNotices()
                Peeks.show(this, getString(R.string.tool_log_cleared), Notice.Level.SUCCESS)
                render()
            })
        }
    }

    /** 줄의 설명에 루시아가 마지막으로 쓴 때를 붙인다. */
    private fun used(hint: Int, switch: ToolSwitch): String {
        val at = hub.settings.toolUsedAt(switch)
        if (at <= 0) return getString(hint)
        return getString(R.string.tool_used, getString(hint), Format.ago(at))
    }

    private fun historySection(parent: LinearLayout) {
        val rows = section(parent, R.string.settings_history)
        switchRow(rows, R.string.settings_owner_history, R.string.settings_owner_history_hint, hub.settings.importOwnerHistory) {
            hub.settings.importOwnerHistory = it
        }
        row(rows, R.string.menu_refresh_history, "", onClick = {
            lifecycleScope.launch {
                hub.refreshHistory().fold(
                    onSuccess = { Toast.makeText(this@SettingsActivity, getString(R.string.history_refreshed, it), Toast.LENGTH_SHORT).show() },
                    onFailure = { Toast.makeText(this@SettingsActivity, R.string.history_refresh_failed, Toast.LENGTH_SHORT).show() },
                )
            }
        })
    }

    private fun aboutSection(parent: LinearLayout) {
        val rows = section(parent, R.string.settings_about)
        row(rows, R.string.app_name, "${BuildConfig.VERSION_NAME} · ${getString(R.string.settings_protocol_short, PROTOCOL_VERSION)}", chevron = false)
    }

    private fun dangerSection(parent: LinearLayout) {
        val rows = section(parent, R.string.settings_disconnect_title, R.string.settings_disconnect_hint)
        val r = row(rows, R.string.settings_disconnect, "", chevron = false, onClick = ::confirmDisconnect)
        r.rowTitle.setTextColor(getColor(R.color.error))
    }

    // ------------------------------------------------------------------ 동작

    private fun editConnection() {
        startActivity(Intent(this, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_EDIT, true))
    }

    private fun askPermission(granted: Boolean) {
        if (granted) return openNotificationSettings()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS).not() &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            hub.settings.askedNotificationPermission
        ) {
            // 이미 거절했으면 시스템이 다시 묻지 않는다. 설정 화면으로 보낸다.
            openNotificationSettings()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hub.settings.askedNotificationPermission = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            openNotificationSettings()
        }
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** 위치 권한을 묻는다. 이미 두 번 거절해 시스템이 더 묻지 않으면 앱 정보로 보낸다. */
    private fun askLocation() {
        val blocked = hub.settings.askedLocationPermission &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
        if (blocked) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            return
        }
        hub.settings.askedLocationPermission = true
        locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    /** 시스템의 알림 접근 화면. 안드로이드 11부터는 이 앱의 항목으로 바로 간다. */
    private fun openListenerSettings() {
        val component = ComponentName(this, NotificationWatcher::class.java).flattenToString()
        val direct = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component)
        } else null
        val opened = direct != null && runCatching { startActivity(direct) }.isSuccess
        if (!opened) runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }

    private fun openNotificationSettings() {
        startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
        )
    }

    private fun registerPush() {
        pushBusy = true
        render()
        lifecycleScope.launch {
            val ok = hub.push.registerCurrent()
            pushBusy = false
            Toast.makeText(this@SettingsActivity, if (ok) R.string.push_register_done else R.string.push_register_failed, Toast.LENGTH_SHORT).show()
            loadServer()
        }
    }

    private fun confirmDisconnect() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_disconnect_confirm_title)
            .setMessage(R.string.settings_disconnect_confirm_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.settings_disconnect) { _, _ ->
                lifecycleScope.launch {
                    hub.disconnect()
                    FileActions.clear(this@SettingsActivity)
                    startActivity(Intent(this@SettingsActivity, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
            }
            .show()
    }

    private fun linkText(state: LinkState): String = when (state) {
        is LinkState.Online -> getString(R.string.status_online, getString(if (state.route == Route.HOME) R.string.route_home else R.string.route_public))
        is LinkState.Connecting, LinkState.Idle -> getString(R.string.status_connecting)
        is LinkState.Waiting -> getString(R.string.settings_status_waiting, state.reason)
        is LinkState.Throttled -> getString(R.string.status_throttled)
        is LinkState.Blocked -> getString(R.string.status_blocked)
    }

    // ------------------------------------------------------------------ 줄 만들기

    private fun section(parent: LinearLayout, title: Int, hint: Int? = null): LinearLayout {
        val b = ViewSectionBinding.inflate(LayoutInflater.from(this), parent, false)
        b.sectionTitle.setText(title)
        if (hint != null) {
            b.sectionHint.setText(hint)
            b.sectionHint.isVisible = true
        }
        // 행을 누를 때의 물결이 면의 둥근 모서리 밖으로 나가지 않게 한다.
        b.sectionRows.clipToOutline = true
        parent.addView(b.root)
        return b.sectionRows
    }

    private fun row(parent: LinearLayout, title: Int, value: String, chevron: Boolean = true, onClick: (() -> Unit)? = null) =
        row(parent, getString(title), value, chevron && onClick != null, onClick)

    private fun row(parent: LinearLayout, title: String, value: String, onClick: () -> Unit) =
        row(parent, title, value, true, onClick)

    private fun row(parent: LinearLayout, title: String, value: String, chevron: Boolean, onClick: (() -> Unit)?): ItemSettingRowBinding {
        divider(parent)
        val b = ItemSettingRowBinding.inflate(LayoutInflater.from(this), parent, false)
        b.rowTitle.text = title
        b.rowValue.text = value
        b.rowValue.isVisible = value.isNotEmpty()
        b.rowChevron.isVisible = chevron
        if (onClick != null) b.root.setOnClickListener { onClick() } else {
            b.root.isClickable = false
            b.root.background = null
        }
        parent.addView(b.root)
        return b
    }

    /** 행과 행 사이의 가는 선. 면을 나누는 대신 선으로 나눈다. 묶음의 첫 행 앞에는 긋지 않는다. */
    private fun divider(parent: LinearLayout) {
        if (parent.isEmpty()) return
        val line = View(this).apply {
            setBackgroundColor(getColor(R.color.line))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val margin = resources.getDimensionPixelSize(R.dimen.space_l)
        parent.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.getDimensionPixelSize(R.dimen.stroke)).apply {
            marginStart = margin
            marginEnd = margin
        })
    }

    /**
     * 휴대폰 기능의 스위치 줄. 설명은 그때그때 바뀐다(권한이 필요하면 그렇게 적는다). [onRowClick]이 있으면 줄을 누를 때
     * 스위치 대신 그것을 한다(권한 묻기). [enabled]가 아니면 흐리게 두고 누를 수 없다.
     */
    private fun toolRow(
        parent: LinearLayout, title: Int, value: String, checked: Boolean,
        enabled: Boolean = true, onRowClick: (() -> Unit)? = null, onChange: (Boolean) -> Unit,
    ) {
        divider(parent)
        val b = ItemSettingRowBinding.inflate(LayoutInflater.from(this), parent, false)
        b.rowTitle.setText(title)
        b.rowValue.text = value
        b.rowChevron.visibility = View.GONE
        b.rowSwitch.isVisible = true
        b.rowSwitch.isChecked = checked
        b.rowSwitch.isEnabled = enabled
        b.rowSwitch.setOnCheckedChangeListener { _, isChecked ->
            onChange(isChecked)
            // 설명과 딸린 줄이 바뀐다. 스위치의 움직임이 끝난 뒤 다시 그린다.
            parent.post { render() }
        }
        if (enabled) {
            b.root.setOnClickListener { onRowClick?.invoke() ?: b.rowSwitch.toggle() }
        } else {
            b.root.isClickable = false
            b.root.background = null
            b.rowTitle.setTextColor(getColor(R.color.ink_faint))
        }
        parent.addView(b.root)
    }

    private fun switchRow(parent: LinearLayout, title: Int, hint: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        divider(parent)
        val b = ItemSettingRowBinding.inflate(LayoutInflater.from(this), parent, false)
        b.rowTitle.setText(title)
        b.rowValue.setText(hint)
        b.rowChevron.visibility = View.GONE
        b.rowSwitch.isVisible = true
        b.rowSwitch.isChecked = checked
        b.rowSwitch.setOnCheckedChangeListener { _, isChecked -> onChange(isChecked) }
        b.root.setOnClickListener { b.rowSwitch.toggle() }
        parent.addView(b.root)
    }

}
