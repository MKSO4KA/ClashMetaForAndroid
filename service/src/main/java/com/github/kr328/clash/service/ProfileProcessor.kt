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

            val rawBody = response.body?.string() ?: ""
            Log.d("[KMS] Данные получены. Размер: ${rawBody.length}")

            val finalYaml = com.github.kr328.clash.service.util.SubConverter.convert(
                rawInput = rawBody,
                pingUrl = params["PingUrl"] ?: "http://cp.cloudflare.com/generate_204",
                pingInterval = params["Ping"] ?: "300",
                pingTolerance = params["Tolerance"] ?: "150"
            )

            val configFile = context.processingDir.resolve("config.yaml")
            configFile.writeText(finalYaml)
            Log.d("[KMS] === ЗАГРУЗКА ЗАВЕРШЕНА УСПЕШНО ===")
        }
    }
}