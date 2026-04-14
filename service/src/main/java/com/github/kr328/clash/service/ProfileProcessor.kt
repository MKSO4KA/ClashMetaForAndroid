package com.github.kr328.clash.service

import android.content.Context
import android.net.Uri
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.Pending
import com.github.kr328.clash.service.data.PendingDao
import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IFetchObserver
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.pendingDir
import com.github.kr328.clash.service.util.processingDir
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.common.util.setUUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.*
import java.util.concurrent.TimeUnit

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

    // --- НАШ ПЕРЕХВАТЧИК ДЛЯ УДАЛЕНИЯ/ВОССТАНОВЛЕНИЯ ---
    suspend fun toggleBlacklist(context: Context, proxyName: String) {
        val store = ServiceStore(context)
        val activeUuid = store.activeProfile ?: return
        val profileDir = context.importedDir.resolve(activeUuid.toString())
        val metadataFile = profileDir.resolve("metadata.json") // Переходим на JSON

        // 1. Читаем существующие списки
        val blacklist = mutableSetOf<String>()
        val whitelist = mutableSetOf<String>()

        if (metadataFile.exists()) {
            try {
                val json = org.json.JSONObject(metadataFile.readText())
                val bArray = json.optJSONArray("blacklist")
                val wArray = json.optJSONArray("whitelist")
                if (bArray != null) for (i in 0 until bArray.length()) blacklist.add(bArray.getString(i))
                if (wArray != null) for (i in 0 until wArray.length()) whitelist.add(wArray.getString(i))
            } catch (e: Exception) { Log.e("Metadata read error: ${e.message}") }
        }

        val isAutoTrash = com.github.kr328.clash.service.util.DecodeUtils.isTrash(proxyName)

        // 2. Умное переключение (Toggle)
        if (blacklist.contains(proxyName)) {
            blacklist.remove(proxyName)
        } else if (whitelist.contains(proxyName)) {
            whitelist.remove(proxyName)
        } else {
            if (isAutoTrash) whitelist.add(proxyName) else blacklist.add(proxyName)
        }

        // 3. Сохраняем обратно в JSON (Senior-way: надежное хранение)
        val newJson = org.json.JSONObject()
        newJson.put("blacklist", org.json.JSONArray(blacklist))
        newJson.put("whitelist", org.json.JSONArray(whitelist))
        metadataFile.writeText(newJson.toString())

        // 4. Мгновенный ребилд
        val rawFile = profileDir.resolve("raw_config.gz")
        val configFile = profileDir.resolve("config.yaml")

        if (rawFile.exists()) {
            val dao = com.github.kr328.clash.service.data.Database.database.openImportedDao()
            val profile = dao.queryByUUID(activeUuid) ?: return
            val params = profile.source.substringAfter("#", "").split("&")
                .filter { it.isNotBlank() }
                .associate { val p = it.split("=", limit = 2); p[0] to URLDecoder.decode(p.getOrNull(1) ?: "", "UTF-8") }
                .toMutableMap()

            params["Blacklist"] = blacklist.joinToString(",")
            params["Whitelist"] = whitelist.joinToString(",")

            com.github.kr328.clash.service.util.SubConverter.convertToFile(
                rawInputBytes = rawFile.readBytes(),
                params = params,
                outputFile = configFile
            )
            context.sendProfileChanged(activeUuid)
        }
    }
    suspend fun apply(context: Context, uuid: UUID, callback: IFetchObserver? = null) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val pending = PendingDao().queryByUUID(uuid)
                        ?: throw IllegalArgumentException("profile $uuid not found")

                    pending.enforceFieldValid()

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.pendingDir.resolve(pending.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    pending
                }

                var force = snapshot.type != Profile.Type.File
                var cb = callback

                if (snapshot.type == Profile.Type.Url) {
                    try {
                        processCustomSubscription(context, snapshot.source)
                        force = false
                    } catch (e: Exception) {
                        Log.w("Custom fetch failed: ${e.message}")
                        throw e
                    }
                }

                Clash.fetchAndValid(context.processingDir, snapshot.source, force) {
                    try { cb?.updateStatus(it) } catch (e: Exception) { cb = null; Log.w("Report fetch status: $e", e) }
                }.await()

                profileLock.withLock {
                    if (PendingDao().queryByUUID(snapshot.uuid) == snapshot) {
                        context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.processingDir.copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))

                        val old = ImportedDao().queryByUUID(snapshot.uuid)
                        val new = com.github.kr328.clash.service.data.Imported(
                            snapshot.uuid, snapshot.name, snapshot.type, snapshot.source, snapshot.interval,
                            old?.upload ?: 0, old?.download ?: 0, old?.total ?: 0, old?.expire ?: 0, old?.createdAt ?: System.currentTimeMillis()
                        )

                        if (old != null) ImportedDao().update(new) else ImportedDao().insert(new)

                        PendingDao().remove(snapshot.uuid)
                        context.pendingDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    suspend fun update(context: Context, uuid: UUID, callback: IFetchObserver?) {
        withContext(NonCancellable) {
            processLock.withLock {
                val snapshot = profileLock.withLock {
                    val imported = ImportedDao().queryByUUID(uuid)
                        ?: throw IllegalArgumentException("profile $uuid not found")

                    context.processingDir.deleteRecursively()
                    context.processingDir.mkdirs()

                    context.importedDir.resolve(imported.uuid.toString())
                        .copyRecursively(context.processingDir, overwrite = true)

                    imported
                }

                var cb = callback
                var force = true

                if (snapshot.type == Profile.Type.Url) {
                    try {
                        processCustomSubscription(context, snapshot.source)
                        force = false
                    } catch (e: Exception) {
                        Log.w("Custom fetch failed: ${e.message}")
                        throw e
                    }
                }

                Clash.fetchAndValid(context.processingDir, snapshot.source, force) {
                    try { cb?.updateStatus(it) } catch (e: Exception) { cb = null; Log.w("Report fetch status: $e", e) }
                }.await()

                profileLock.withLock {
                    if (ImportedDao().exists(snapshot.uuid)) {
                        context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.processingDir.copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))
                        context.sendProfileChanged(snapshot.uuid)
                    }
                }
            }
        }
    }

    suspend fun delete(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                ImportedDao().remove(uuid)
                PendingDao().remove(uuid)
                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
                context.importedDir.resolve(uuid.toString()).deleteRecursively()
                context.sendProfileChanged(uuid)
            }
        }
    }

    suspend fun release(context: Context, uuid: UUID): Boolean {
        return withContext(NonCancellable) {
            profileLock.withLock {
                PendingDao().remove(uuid)
                context.pendingDir.resolve(uuid.toString()).deleteRecursively()
                true
            }
        }
    }

    suspend fun active(context: Context, uuid: UUID) {
        withContext(NonCancellable) {
            profileLock.withLock {
                if (ImportedDao().exists(uuid)) {
                    val store = ServiceStore(context)
                    store.activeProfile = uuid
                    context.sendProfileChanged(uuid)
                }
            }
        }
    }

    private fun Pending.enforceFieldValid() {
        val scheme = Uri.parse(source)?.scheme?.lowercase(Locale.getDefault())
        when {
            name.isBlank() -> throw IllegalArgumentException("Empty name")
            source.isEmpty() && type != Profile.Type.File -> throw IllegalArgumentException("Invalid url")
            source.isNotEmpty() && scheme != "https" && scheme != "http" && scheme != "content" -> throw IllegalArgumentException("Unsupported url $source")
            interval != 0L && TimeUnit.MILLISECONDS.toMinutes(interval) < 15 -> throw IllegalArgumentException("Invalid interval")
        }
    }

    private fun processCustomSubscription(context: Context, fullUrl: String) {
        Log.d("[KMS] === СТАРТ ЗАГРУЗКИ ===")

        val realUrl = fullUrl.substringBefore("#")
        val params = if (fullUrl.contains("#")) {
            val fragment = fullUrl.substringAfter("#")
            fragment.split("&").filter { it.isNotBlank() }.associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8"))
            }.toMutableMap()
        } else mutableMapOf<String, String>()

        // SENIOR FIX: Читаем сохраненные списки из JSON
        val metadataFile = context.processingDir.resolve("metadata.json")
        if (metadataFile.exists()) {
            try {
                val json = org.json.JSONObject(metadataFile.readText())
                val bArray = json.optJSONArray("blacklist")
                val wArray = json.optJSONArray("whitelist")

                val bList = mutableListOf<String>()
                val wList = mutableListOf<String>()

                if (bArray != null) for (i in 0 until bArray.length()) bList.add(bArray.getString(i))
                if (wArray != null) for (i in 0 until wArray.length()) wList.add(wArray.getString(i))

                if (bList.isNotEmpty()) params["Blacklist"] = bList.joinToString(",")
                if (wList.isNotEmpty()) params["Whitelist"] = wList.joinToString(",")
                Log.d("[KMS] Метаданные загружены успешно из JSON.")
            } catch (e: Exception) { Log.e("Metadata processing error: ${e.message}") }
        }

        val autoHwid = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "00000000"
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val reqBuilder = Request.Builder().url(realUrl)
            .header("User-Agent", params["UA"] ?: "Happ/3.16.1/Android/1743595")
            .header("x-device-model", params["Model"] ?: android.os.Build.MODEL)
            .header("x-hwid", params["HWID"] ?: autoHwid)
            .header("x-app-version", params["AppVer"] ?: "3.16.1")

        try {
            client.newCall(reqBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Server error ${response.code}")
                val responseBytes = response.body?.bytes() ?: throw Exception("Empty response")

                val isAlreadyGzipped = responseBytes.size >= 2 && responseBytes[0].toInt() == 0x1F && (responseBytes[1].toInt() and 0xFF) == 0x8B
                val finalRawBytes = if (isAlreadyGzipped) responseBytes else {
                    val baos = ByteArrayOutputStream()
                    java.util.zip.GZIPOutputStream(baos).use { it.write(responseBytes) }
                    baos.toByteArray()
                }

                context.processingDir.resolve("raw_config.gz").writeBytes(finalRawBytes)

                com.github.kr328.clash.service.util.SubConverter.convertToFile(
                    rawInputBytes = finalRawBytes,
                    params = params,
                    outputFile = context.processingDir.resolve("config.yaml")
                )
            }
        } catch (e: Exception) {
            Log.w("[KMS] Сеть недоступна, используем кэш...")
            val cachedRaw = context.processingDir.resolve("raw_config.gz")
            if (cachedRaw.exists()) {
                com.github.kr328.clash.service.util.SubConverter.convertToFile(
                    rawInputBytes = cachedRaw.readBytes(),
                    params = params,
                    outputFile = context.processingDir.resolve("config.yaml")
                )
            } else throw e
        }
    }
}