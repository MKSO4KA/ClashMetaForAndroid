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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.*
import java.util.concurrent.TimeUnit
import java.net.URLEncoder
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.common.util.setUUID

object ProfileProcessor {
    private val profileLock = Mutex()
    private val processLock = Mutex()

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

                // --- НАШ ПЕРЕХВАТЧИК ---
                if (snapshot.type == Profile.Type.Url) {
                    try {
                        processCustomSubscription(context, snapshot.source)
                        force = false
                    } catch (e: Exception) {
                        Log.w("Custom fetch failed: ${e.message}")
                        throw e // <--- ВОТ ЭТУ СТРОКУ ДОБАВЬ ОБЯЗАТЕЛЬНО!
                    }
                }

                Clash.fetchAndValid(context.processingDir, snapshot.source, force) {
                    try {
                        cb?.updateStatus(it)
                    } catch (e: Exception) {
                        cb = null
                        Log.w("Report fetch status: $e", e)
                    }
                }.await()

                profileLock.withLock {
                    if (PendingDao().queryByUUID(snapshot.uuid) == snapshot) {
                        context.importedDir.resolve(snapshot.uuid.toString()).deleteRecursively()
                        context.processingDir.copyRecursively(context.importedDir.resolve(snapshot.uuid.toString()))

                        // Перемещаем Pending в Imported
                        val old = ImportedDao().queryByUUID(snapshot.uuid)
                        val new = com.github.kr328.clash.service.data.Imported(
                            snapshot.uuid,
                            snapshot.name,
                            snapshot.type,
                            snapshot.source,
                            snapshot.interval,
                            old?.upload ?: 0,
                            old?.download ?: 0,
                            old?.total ?: 0,
                            old?.expire ?: 0,
                            old?.createdAt ?: System.currentTimeMillis()
                        )

                        if (old != null) {
                            ImportedDao().update(new)
                        } else {
                            ImportedDao().insert(new)
                        }

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

                // --- НАШ ПЕРЕХВАТЧИК ---
                if (snapshot.type == Profile.Type.Url) {
                    try {
                        processCustomSubscription(context, snapshot.source)
                        force = false
                    } catch (e: Exception) {
                        Log.w("Custom fetch failed: ${e.message}")
                        throw e // <--- ВОТ ЭТУ СТРОКУ ДОБАВЬ ОБЯЗАТЕЛЬНО!
                    }
                }

                Clash.fetchAndValid(context.processingDir, snapshot.source, force) {
                    try {
                        cb?.updateStatus(it)
                    } catch (e: Exception) {
                        cb = null
                        Log.w("Report fetch status: $e", e)
                    }
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
        Log.d("[KMS] === СТАРТ КАСТОМНОЙ ЗАГРУЗКИ ===")

        val realUrl = fullUrl.substringBefore("#")
        val params = if (fullUrl.contains("#")) {
            val fragment = fullUrl.substringAfter("#")
            fragment.split("&").filter { it.isNotBlank() }.associate {
                val parts = it.split("=")
                parts[0] to (URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8"))
            }
        } else emptyMap()

        val autoHwid = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "00000000"

        val client = OkHttpClient()
        val reqBuilder = Request.Builder().url(realUrl)

        // Заголовки
        reqBuilder.header("User-Agent", params["UA"] ?: "Happ/3.16.1/Android/1743595")
        reqBuilder.header("x-device-model", params["Model"] ?: android.os.Build.MODEL)
        reqBuilder.header("x-hwid", params["HWID"] ?: autoHwid)
        reqBuilder.header("x-device-os", params["OS"] ?: "Android")
        reqBuilder.header("x-ver-os", params["OSVer"] ?: android.os.Build.VERSION.RELEASE)
        reqBuilder.header("x-app-version", params["AppVer"] ?: "3.16.1")
        reqBuilder.header("accept-encoding", params["Encoding"] ?: "gzip")
        reqBuilder.header("x-device-locale", params["Locale"] ?: java.util.Locale.getDefault().language)
        reqBuilder.header("accept-language", params["Lang"] ?: "ru-RU,en;q=0.9")

        Log.d("[KMS] Отправка запроса на: $realUrl")

        client.newCall(reqBuilder.build()).execute().use { response ->
            Log.d("[KMS] HTTP Статус: ${response.code}")

            if (!response.isSuccessful) {
                Log.e("[KMS] СЕРВЕР ОТВЕТИЛ ОШИБКОЙ: ${response.code}")
                throw Exception("Server returned error ${response.code}")
            }

            val isGzipped = response.header("Content-Encoding") == "gzip"

            val rawBody = if (isGzipped) {
                // Если сжато - распаковываем вручную
                java.util.zip.GZIPInputStream(response.body?.byteStream()).bufferedReader().use { it.readText() }
            } else {
                // Если не сжато - читаем как обычно
                response.body?.string() ?: ""
            }

            Log.d("[KMS] Данные получены. Размер (после распаковки): ${rawBody.length}")
            if (rawBody.isNotEmpty()) {
                Log.d("[KMS] Первые 20 символов тела: ${rawBody.take(20)}")
            }
            Log.d("[KMS] Данные получены. Размер: ${rawBody.length}")

            val rawFile = context.processingDir.resolve("raw_config.txt")
            rawFile.writeText(rawBody)

            val finalYaml = com.github.kr328.clash.service.util.SubConverter.convert(
                rawInput = rawBody,
                params = params
            )

            val configFile = context.processingDir.resolve("config.yaml")
            configFile.writeText(finalYaml)
            Log.d("[KMS] === ЗАГРУЗКА ЗАВЕРШЕНА УСПЕШНО ===")
        }
    }

    suspend fun toggleBlacklist(context: Context, proxyName: String) {
        val store = com.github.kr328.clash.service.store.ServiceStore(context)
        val activeUuid = store.activeProfile ?: return
        val dao = com.github.kr328.clash.service.data.Database.database.openImportedDao()
        val profile = dao.queryByUUID(activeUuid) ?: return

        // 1. Модифицируем параметры URL
        val currentUrl = profile.source
        val baseUrl = currentUrl.substringBefore("#")
        val fragment = if (currentUrl.contains("#")) currentUrl.substringAfter("#") else ""

        val queryMap = fragment.split("&")
            .filter { s -> s.isNotBlank() }
            .associate { s ->
                val parts = s.split("=", limit = 2)
                parts[0] to java.net.URLDecoder.decode(parts.getOrNull(1) ?: "", "UTF-8")
            }.toMutableMap()

        val blacklist = (queryMap["Blacklist"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        val whitelist = (queryMap["Whitelist"] ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()

        val isAutoTrash = com.github.kr328.clash.service.util.DecodeUtils.isTrash(proxyName)

        if (blacklist.contains(proxyName)) {
            blacklist.remove(proxyName)
        } else if (whitelist.contains(proxyName)) {
            whitelist.remove(proxyName)
        } else {
            if (isAutoTrash) whitelist.add(proxyName) else blacklist.add(proxyName)
        }

        queryMap["Blacklist"] = blacklist.joinToString(",")
        queryMap["Whitelist"] = whitelist.joinToString(",")

        val newFragment = queryMap.entries.joinToString("&") { e ->
            "${e.key}=${java.net.URLEncoder.encode(e.value, "UTF-8")}"
        }
        val newUrl = "$baseUrl#$newFragment"

        dao.update(profile.copy(source = newUrl))

        // 2. Мгновенный локальный ребилд (ИСПРАВЛЕННЫЙ СИНТАКСИС)
        // Здесь мы используем context.importedDir как свойство, а не функцию
        val profileDir = context.importedDir.resolve(activeUuid.toString())
        val rawFile = profileDir.resolve("raw_config.txt")
        val configFile = profileDir.resolve("config.yaml")

        if (rawFile.exists()) {
            try {
                val rawBody = rawFile.readText()
                val finalYaml = com.github.kr328.clash.service.util.SubConverter.convert(
                    rawInput = rawBody,
                    params = queryMap
                )
                configFile.writeText(finalYaml)

                // Здесь вызываем как расширение контекста
                context.sendProfileChanged(activeUuid)
                return
            } catch (e: Exception) {
                com.github.kr328.clash.common.log.Log.e("Local rebuild failed: ${e.message}")
            }
        }

        // 3. Фоллбек
        val intent = android.content.Intent(com.github.kr328.clash.common.constants.Intents.ACTION_PROFILE_REQUEST_UPDATE).apply {
            setPackage(context.packageName)
            setUUID(activeUuid)
        }
        context.startService(intent)
    }
}