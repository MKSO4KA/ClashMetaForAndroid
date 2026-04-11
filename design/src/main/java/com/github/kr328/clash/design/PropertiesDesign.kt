package com.github.kr328.clash.design

import android.content.Context
import android.view.View
import android.widget.Toast
import com.github.kr328.clash.common.compat.fromHtmlCompat
import com.github.kr328.clash.core.model.FetchStatus
import com.github.kr328.clash.design.databinding.DesignPropertiesBinding
import com.github.kr328.clash.design.dialog.ModelProgressBarConfigure
import com.github.kr328.clash.design.dialog.requestModelTextInput
import com.github.kr328.clash.design.dialog.withModelProgressBar
import com.github.kr328.clash.design.util.*
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.util.HttpSniffer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

class PropertiesDesign(context: Context) : Design<PropertiesDesign.Request>(context) {
    sealed class Request {
        object Commit : Request()
        object BrowseFiles : Request()
    }

    private val binding = DesignPropertiesBinding.inflate(context.layoutInflater, context.root, false)
    override val root: View get() = binding.root
    var profile: Profile get() = binding.profile!!; set(value) { binding.profile = value }
    val progressing: Boolean get() = binding.processing

    // ================== УПРАВЛЕНИЕ ВИДИМОСТЬЮ ==================
    var advanced: Boolean = false
        set(value) {
            field = value
            binding.advanced = value
        }

    fun toggleAdvanced() {
        advanced = !advanced
    }

    // ================== БАЗОВЫЕ ФУНКЦИИ ==================
    suspend fun withProcessing(executeTask: suspend (suspend (FetchStatus) -> Unit) -> Unit) {
        try {
            binding.processing = true
            context.withModelProgressBar {
                configure { isIndeterminate = true; text = context.getString(R.string.initializing) }
                executeTask { configure { applyFrom(it) } }
            }
        } finally { binding.processing = false }
    }

    suspend fun requestExitWithoutSaving(): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { ctx ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(R.string.exit_without_save)
                .setMessage(R.string.exit_without_save_warning)
                .setPositiveButton(R.string.ok) { _, _ -> ctx.resume(true) }
                .setNegativeButton(R.string.cancel) { _, _ -> }
                .setOnDismissListener { if (!ctx.isCompleted) ctx.resume(false) }
                .show()
            ctx.invokeOnCancellation { dialog.dismiss() }
        }
    }

    init {
        binding.self = this
        binding.activityBarLayout.applyFrom(context)
        binding.tips.text = context.getHtml(R.string.tips_properties)
        binding.scrollRoot.bindAppBarElevation(binding.activityBarLayout)
    }

    fun inputName() { launch { val name = context.requestModelTextInput(initial = profile.name, title = context.getText(R.string.name), hint = context.getText(R.string.properties), error = context.getText(R.string.should_not_be_blank), validator = ValidatorNotBlank); if (name != profile.name) profile = profile.copy(name = name) } }
    fun inputInterval() { launch { var minutes = TimeUnit.MILLISECONDS.toMinutes(profile.interval); minutes = context.requestModelTextInput(initial = if (minutes == 0L) "" else minutes.toString(), title = context.getText(R.string.auto_update), hint = context.getText(R.string.auto_update_minutes), error = context.getText(R.string.at_least_15_minutes), validator = ValidatorAutoUpdateInterval).toLongOrNull() ?: 0; val interval = TimeUnit.MINUTES.toMillis(minutes); if (interval != profile.interval) profile = profile.copy(interval = interval) } }
    fun requestCommit() { requests.trySend(Request.Commit) }
    fun requestBrowseFiles() { requests.trySend(Request.BrowseFiles) }

    // ================== РАБОТА С URL (ЧИСТОЕ ОТОБРАЖЕНИЕ) ==================
    fun getDisplayUrl(url: String): String {
        return url.substringBefore("#")
    }

    fun inputUrl() {
        if (profile.type == Profile.Type.External) return
        launch {
            val currentFull = profile.source
            val currentBase = currentFull.substringBefore("#")
            val input = context.requestModelTextInput(
                initial = currentBase,
                title = context.getText(R.string.url),
                hint = context.getText(R.string.profile_url),
                error = context.getText(R.string.accept_http_content),
                validator = ValidatorHttpUrl
            )
            if (input != currentBase) {
                val newBase = input.substringBefore("#")
                val oldFragment = if (currentFull.contains("#")) currentFull.substringAfter("#") else ""
                profile = profile.copy(source = if (oldFragment.isEmpty()) newBase else "$newBase#$oldFragment")
                binding.invalidateAll()
            }
        }
    }

    // ================== МЕНЕДЖМЕНТ ПАРАМЕТРОВ (9 ПОЛЕЙ) ==================
    private fun parseUrlFragment(url: String): Map<String, String> {
        val fragment = url.substringAfter("#")
        return fragment.split("&").filter { it.isNotBlank() }.associate {
            val parts = it.split("=")
            parts[0] to (parts.getOrNull(1)?.let { v -> URLDecoder.decode(v, "UTF-8") } ?: "")
        }
    }

    private fun updateUrlParam(url: String, key: String, value: String): String {
        val base = url.substringBefore("#")
        val params = if (url.contains("#")) parseUrlFragment(url).toMutableMap() else mutableMapOf()

        if (value.isBlank()) params.remove(key) else params[key] = value
        if (params.isEmpty()) return base

        val newFragment = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        return "$base#$newFragment"
    }

    fun getParam(url: String, key: String): String {
        if (!url.contains("#")) return ""
        return parseUrlFragment(url)[key] ?: ""
    }

    fun inputHeader(key: String, title: String) {
        launch {
            val current = getParam(profile.source, key)
            val newVal = context.requestModelTextInput(initial = current, title = title, hint = "Value", error = "", validator = { true })

            if (newVal != current) {
                var newSource = updateUrlParam(profile.source, key, newVal)
                newSource = updateUrlParam(newSource, "PresetId", "") // Сбрасываем пресет при ручном вводе
                profile = profile.copy(source = newSource)
                binding.invalidateAll()
            }
        }
    }

    // ================== ПРЕСЕТЫ И РЕДАКТОР ==================
    fun getCurrentPresetName(url: String): String {
        if (!url.contains("#")) return context.getString(R.string.preset_custom)
        val params = parseUrlFragment(url)
        val presetId = params["PresetId"]?.toIntOrNull()
        if (presetId != null) {
            val matched = DeviceProfileManager.loadProfiles(context).find { it.id == presetId }
            if (matched != null) return matched.name
        }
        return context.getString(R.string.preset_custom)
    }

    fun selectDevicePreset() {
        launch {
            val profiles = DeviceProfileManager.loadProfiles(context)
            val dialogView = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_device_profiles, null)
            val recyclerView = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_view)!!

            val listDialog = MaterialAlertDialogBuilder(context).setView(dialogView).show()

            val adapter = com.github.kr328.clash.design.adapter.DeviceProfileAdapter(
                profiles = profiles,
                onItemClick = { selected ->
                    applyPresetConfig(selected)
                    listDialog.dismiss()
                },
                onEditClick = { item ->
                    listDialog.dismiss()
                    showProfileEditor(item)
                },
                onDeleteClick = { item ->
                    val list = DeviceProfileManager.loadProfiles(context)
                    list.removeAll { it.id == item.id }
                    DeviceProfileManager.saveProfiles(context, list)
                    listDialog.dismiss()
                    selectDevicePreset()
                }
            )
            recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            recyclerView.adapter = adapter
        }
    }

    private fun showProfileEditor(item: DeviceProfile) {
        val view = android.view.LayoutInflater.from(context).inflate(R.layout.dialog_device_profile_editor, null)
        val editName = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_name)
        val editUA = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_ua)
        val editModel = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_model)
        val editExtra = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.edit_extra)

        val params = parseUrlFragment("#${item.config}")
        editName.setText(item.name)
        editUA.setText(params["UA"] ?: "")
        editModel.setText(params["Model"] ?: "")

        val extraHeaders = params.filterKeys { it != "UA" && it != "Model" }
            .entries.joinToString("&") { "${it.key}=${it.value}" }
        editExtra.setText(extraHeaders)

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.device_preset)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val newName = editName.text.toString()
                val newParams = mutableListOf<String>()
                if (editUA.text?.isNotBlank() == true) newParams.add("UA=${editUA.text}")
                if (editModel.text?.isNotBlank() == true) newParams.add("Model=${editModel.text}")
                if (editExtra.text?.isNotBlank() == true) newParams.add(editExtra.text.toString())

                val newConfigString = newParams.joinToString("&")
                if (newName.isNotBlank()) {
                    val list = DeviceProfileManager.loadProfiles(context)
                    val idx = list.indexOfFirst { it.id == item.id }
                    if (idx != -1) {
                        list[idx] = list[idx].copy(name = newName, config = newConfigString)
                        DeviceProfileManager.saveProfiles(context, list)
                    }
                    selectDevicePreset()
                }
            }
            .setNegativeButton(R.string.action_cancel) { _, _ -> selectDevicePreset() }
            .show()
    }

    private fun applyPresetConfig(preset: DeviceProfile) {
        val baseUrl = profile.source.substringBefore("#")
        val currentParams = if (profile.source.contains("#")) parseUrlFragment(profile.source).toMutableMap() else mutableMapOf()

        val pingParams = currentParams.filterKeys { it.startsWith("Ping") || it == "Tolerance" }

        val rawPreset = parseUrlFragment("#${preset.config}")
        val mappedPreset = mutableMapOf<String, String>()
        rawPreset.forEach { (k, v) ->
            val newKey = when(k.lowercase()) {
                "user-agent", "ua" -> "UA"
                "x-device-model", "model" -> "Model"
                "x-hwid", "hwid" -> "HWID"
                "x-device-os", "os" -> "OS"
                "x-ver-os", "osver" -> "OSVer"
                "x-app-version", "appver" -> "AppVer"
                "accept-encoding", "encoding" -> "Encoding"
                "x-device-locale", "locale" -> "Locale"
                "accept-language", "lang" -> "Lang"
                else -> k
            }
            mappedPreset[newKey] = v
        }

        val finalParams = pingParams + mappedPreset + ("PresetId" to preset.id.toString())
        val newFrag = finalParams.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }

        val newProfile = profile.copy(source = "$baseUrl#$newFrag")
        profile = newProfile
        binding.profile = newProfile
        binding.invalidateAll()
    }

    // ================== РАДАР (СНИФФЕР) ==================
    fun startCapture() {
        var captureJob: Job? = null
        val snifferPort = 48329
        val snifferUrl = "http://127.0.0.1:$snifferPort/"

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.capture_radar_title)
            .setMessage(fromHtmlCompat(context.getString(R.string.capture_radar_message, snifferUrl)))
            .setPositiveButton(R.string.action_copy_url, null)
            .setNegativeButton(R.string.action_cancel) { _, _ -> captureJob?.cancel() }
            .setOnDismissListener { captureJob?.cancel() }
            .create()

        dialog.show()

        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("url", snifferUrl))
            Toast.makeText(context, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }

        captureJob = launch {
            val result = HttpSniffer.capture(snifferPort)
            withContext(Dispatchers.Main) {
                if (result is HttpSniffer.Result.Success) {
                    dialog.dismiss()

                    val headers = result.headers
                    val model = headers["x-device-model"] ?: "Device"
                    val ua = headers["user-agent"] ?: "Unknown"
                    val generatedName = "$model / ${ua.substringBefore("/").take(8)}"

                    val params = mutableListOf<String>()
                    val allowedKeys = setOf("UA", "Model", "HWID", "OS", "OSVer", "AppVer", "Encoding", "Locale", "Lang")

                    headers.forEach { (k, v) ->
                        val key = when(k.lowercase()) {
                            "user-agent" -> "UA"
                            "x-device-model" -> "Model"
                            "x-hwid" -> "HWID"
                            "x-device-os" -> "OS"
                            "x-ver-os" -> "OSVer"
                            "x-app-version" -> "AppVer"
                            "accept-encoding" -> "Encoding"
                            "x-device-locale" -> "Locale"
                            "accept-language" -> "Lang"
                            else -> k
                        }
                        if (key in allowedKeys) {
                            params.add("$key=${URLEncoder.encode(v, "UTF-8")}")
                        }
                    }
                    val configString = params.joinToString("&")

                    val list = DeviceProfileManager.loadProfiles(context)
                    val nextId = (list.maxOfOrNull { it.id } ?: 0) + 1
                    val newCapturedProfile = DeviceProfile(nextId, generatedName, configString, false)
                    list.add(newCapturedProfile)
                    DeviceProfileManager.saveProfiles(context, list)

                    applyPresetConfig(newCapturedProfile)
                    advanced = true // Разворачиваем настройки

                    Toast.makeText(context, "${context.getString(R.string.capture_success)}: $generatedName", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ================== НАСТРОЙКИ ПИНГА ==================
    fun getPingInterval(url: String): String = getParam(url, "Ping").ifBlank { "300" }
    fun getTolerance(url: String): String = getParam(url, "Tolerance").ifBlank { "150" }
    fun getPingUrl(url: String): String = getParam(url, "PingUrl").ifBlank { "http://cp.cloudflare.com/generate_204" }

    fun inputPingInterval() { launch { val c = getPingInterval(profile.source); val n = context.requestModelTextInput(initial = c, title = context.getText(R.string.ping_interval), hint = "300", error = "", validator = { true }); if (n != c) profile = profile.copy(source = updateUrlParam(profile.source, "Ping", n)) } }
    fun inputTolerance() { launch { val c = getTolerance(profile.source); val n = context.requestModelTextInput(initial = c, title = context.getText(R.string.ping_tolerance), hint = "150", error = "", validator = { true }); if (n != c) profile = profile.copy(source = updateUrlParam(profile.source, "Tolerance", n)) } }
    fun inputPingUrl() { launch { val c = getPingUrl(profile.source); val n = context.requestModelTextInput(initial = c, title = context.getText(R.string.ping_url), hint = "http://cp.cloudflare.com/generate_204", error = "", validator = { true }); if (n != c) profile = profile.copy(source = updateUrlParam(profile.source, "PingUrl", n)) } }

    private fun ModelProgressBarConfigure.applyFrom(status: FetchStatus) {
        when (status.action) {
            FetchStatus.Action.FetchConfiguration -> { text = context.getString(R.string.format_fetching_configuration, status.args[0]); isIndeterminate = true }
            FetchStatus.Action.FetchProviders -> { text = context.getString(R.string.format_fetching_provider, status.args[0]); isIndeterminate = false; max = status.max; progress = status.progress }
            FetchStatus.Action.Verifying -> { text = context.getString(R.string.verifying); isIndeterminate = false; max = status.max; progress = status.progress }
        }
    }
}