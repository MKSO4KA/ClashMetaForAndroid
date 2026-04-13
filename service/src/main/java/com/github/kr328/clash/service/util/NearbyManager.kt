package com.github.kr328.clash.service.util

import android.content.Context
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.service.data.Database
import com.github.kr328.clash.service.data.Imported
import com.github.kr328.clash.service.model.Profile
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.UUID
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileChanged
import com.github.kr328.clash.service.util.generateProfileUUID

object NearbyManager {
    private const val SERVICE_ID = "com.github.kr328.clash.MESH_BRIDGE"

    // --- ОТПРАВИТЕЛЬ (ПОКАЗЫВАЕТ QR) ---
    fun startSharing(context: Context, profileUuid: UUID, pairingToken: String, onStatus: (String, Boolean) -> Unit) {
        val client = Nearby.getConnectionsClient(context)
        val payloadBytes = runBlocking(Dispatchers.IO) { buildProfilePayload(context, profileUuid) }

        if (payloadBytes == null) {
            onStatus("Ошибка: Конфиг не найден", true)
            return
        }

        // Стратегия STAR идеальна для быстрой передачи 1 к 1
        val options = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_STAR).build()

        val callback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                // Молча принимаем подключение, так как если нас нашли - значит отсканировали наш QR
                onStatus("Устройство подключено! Отправка...", false)
                client.acceptConnection(endpointId, PayloadCallbackHelper())
            }

            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (result.status.isSuccess) {
                    client.sendPayload(endpointId, Payload.fromBytes(payloadBytes))
                    // Даем 3 секунды на завершение потока и закрываем лавочку
                    GlobalScope.launch { delay(3000); stopAll(context); onStatus("Профиль успешно передан!", true) }
                } else {
                    onStatus("Сбой подключения", true)
                    stopAll(context)
                }
            }

            override fun onDisconnected(endpointId: String) { stopAll(context) }
        }

        // Мы транслируем в эфир наш токен (pairingToken) вместо имени телефона
        client.startAdvertising(pairingToken, SERVICE_ID, callback, options)
            .addOnSuccessListener { onStatus("Ждем сканирования...", false) }
            .addOnFailureListener { onStatus("Ошибка Nearby: ${it.message}", true) }
    }

    // --- ПОЛУЧАТЕЛЬ (СКАНИРУЕТ QR) ---
    fun startReceiving(context: Context, targetToken: String, onStatus: (String, Boolean) -> Unit) {
        val client = Nearby.getConnectionsClient(context)
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).build()

        val payloadCallback = object : PayloadCallback() {
            override fun onPayloadReceived(endpointId: String, payload: Payload) {
                val bytes = payload.asBytes() ?: return
                onStatus("Данные получены. Установка...", false)
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        processReceivedPayload(context, bytes)
                        withContext(Dispatchers.Main) { onStatus("Профиль успешно установлен!", true) }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { onStatus("Ошибка сохранения: ${e.message}", true) }
                    } finally {
                        stopAll(context)
                    }
                }
            }
            override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
        }

        val connCallback = object : ConnectionLifecycleCallback() {
            override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
                client.acceptConnection(endpointId, payloadCallback)
            }
            override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
                if (!result.status.isSuccess) {
                    onStatus("Ошибка связи с отправителем", true)
                    stopAll(context)
                }
            }
            override fun onDisconnected(endpointId: String) {}
        }

        val discoveryCallback = object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                // КРИТИЧЕСКИ ВАЖНО: Мы подключаемся ТОЛЬКО если имя устройства совпадает с токеном из QR!
                if (info.endpointName == targetToken) {
                    onStatus("Отправитель найден! Подключение...", false)
                    client.requestConnection(android.os.Build.MODEL, endpointId, connCallback)
                }
            }
            override fun onEndpointLost(endpointId: String) {}
        }

        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnSuccessListener { onStatus("Поиск устройства по QR-коду...", false) }
            .addOnFailureListener { onStatus("Ошибка сканирования: ${it.message}", true) }
    }

    fun stopAll(context: Context) {
        val client = Nearby.getConnectionsClient(context)
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
    }

    // --- СЕРВИСНЫЕ МЕТОДЫ УПАКОВКИ/РАСПАКОВКИ ---
    // --- СЕРВИСНЫЕ МЕТОДЫ УПАКОВКИ/РАСПАКОВКИ ---
    private suspend fun buildProfilePayload(context: Context, uuid: UUID): ByteArray? {
        val profileDir = context.importedDir.resolve(uuid.toString())
        val rawFile = profileDir.resolve("raw_config.gz")
        val metaFile = profileDir.resolve("metadata.txt") // Наш файл со списками

        if (!rawFile.exists()) return null

        val dao = Database.database.openImportedDao()
        val profile = dao.queryByUUID(uuid) ?: return null

        val json = JSONObject()
        json.put("name", "${profile.name} (Mesh)")
        json.put("sourceUrl", profile.source) // Здесь живут Mux, Fragment, UA и т.д.

        // Читаем метаданные (черный/белый списки), если они есть
        val metaData = if (metaFile.exists()) metaFile.readText() else ":"
        json.put("metaData", metaData)

        // Бинарный конфиг в Base64
        json.put("rawConfigGzipB64", android.util.Base64.encodeToString(rawFile.readBytes(), android.util.Base64.NO_WRAP))

        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private suspend fun processReceivedPayload(context: Context, bytes: ByteArray) {
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val newUuid = generateProfileUUID()
        val sourceUrl = json.getString("sourceUrl")
        val rawConfigGzipBytes = android.util.Base64.decode(json.getString("rawConfigGzipB64"), android.util.Base64.NO_WRAP)
        val receivedMeta = json.optString("metaData", ":")

        // 1. Создаем запись в БД (сохраняем sourceUrl со всеми параметрами #...)
        val dao = Database.database.openImportedDao()
        val newProfile = Imported(
            uuid = newUuid, name = json.getString("name"), type = Profile.Type.Url,
            source = sourceUrl, interval = 0, upload = 0, download = 0, total = 0, expire = 0, createdAt = System.currentTimeMillis()
        )

        // 2. Подготавливаем файлы
        val profileDir = context.importedDir.resolve(newUuid.toString())
        profileDir.mkdirs()

        profileDir.resolve("raw_config.gz").writeBytes(rawConfigGzipBytes)
        profileDir.resolve("metadata.txt").writeText(receivedMeta) // Клонируем черный список

        // 3. Собираем финальный YAML из полученных данных
        val parts = receivedMeta.split(":")
        val blacklist = parts.getOrElse(0) { "" }.replace("|", ",")
        val whitelist = parts.getOrElse(1) { "" }.replace("|", ",")

        val queryMap = (if (sourceUrl.contains("#")) sourceUrl.substringAfter("#") else "").split("&")
            .filter { it.isNotBlank() }
            .associate { val p = it.split("=", limit = 2); p[0] to java.net.URLDecoder.decode(p.getOrNull(1) ?: "", "UTF-8") }
            .toMutableMap()

        // Добавляем списки в параметры конвертера
        queryMap["Blacklist"] = blacklist
        queryMap["Whitelist"] = whitelist

        SubConverter.convertToFile(rawConfigGzipBytes, queryMap, profileDir.resolve("config.yaml"))

        dao.insert(newProfile)
        context.sendProfileChanged(newUuid)
    }

    private class PayloadCallbackHelper : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {}
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }
    // --- ПУБЛИЧНЫЙ ВРАППЕР ДЛЯ ADB ТЕСТОВ ---
    suspend fun processReceivedPayloadPublic(context: Context, bytes: ByteArray) {
        processReceivedPayload(context, bytes)
    }
}