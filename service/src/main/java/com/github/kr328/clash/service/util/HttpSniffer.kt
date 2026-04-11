package com.github.kr328.clash.service.util

import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.ServerSocket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread
import kotlin.coroutines.resume

object HttpSniffer {
    sealed class Result {
        data class Success(val headers: Map<String, String>) : Result()
        object Timeout : Result()
        data class Error(val message: String) : Result()
    }

    suspend fun capture(port: Int, timeoutMs: Int = 60000): Result = suspendCancellableCoroutine { cont ->
        // Запускаем в отдельном потоке, чтобы блокирующий accept() не повесил корутину
        thread {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(port)
                server.soTimeout = timeoutMs

                // Если пользователь нажал "Отмена" в интерфейсе — принудительно гасим сервер и освобождаем порт!
                cont.invokeOnCancellation {
                    try { server?.close() } catch (e: Exception) {}
                }

                val client = server.accept()
                val reader = client.getInputStream().bufferedReader()
                val headers = mutableMapOf<String, String>()

                // Пропускаем первую строку (GET / HTTP/1.1)
                reader.readLine() ?: throw Exception("Empty request")

                // Читаем заголовки
                var line = reader.readLine()
                while (!line.isNullOrBlank()) {
                    val parts = line.split(": ", limit = 2)
                    if (parts.size == 2) {
                        headers[parts[0].lowercase()] = parts[1]
                    }
                    line = reader.readLine()
                }

                // Отправляем фейковый ответ, чтобы другое приложение не зависло с ошибкой
                val writer = client.getOutputStream().bufferedWriter()
                writer.write("HTTP/1.1 200 OK\r\n")
                writer.write("Content-Type: application/json\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.write("{\"proxies\":[]}")
                writer.flush()

                client.close()

                if (cont.isActive) cont.resume(Result.Success(headers))

            } catch (e: SocketTimeoutException) {
                if (cont.isActive) cont.resume(Result.Timeout)
            } catch (e: Exception) {
                // Игнорируем ошибку SocketException, если она вызвана нашим же закрытием порта при отмене
                if (cont.isActive) cont.resume(Result.Error(e.message ?: "Unknown error"))
            } finally {
                // Гарантированно закрываем порт
                try { server?.close() } catch (e: Exception) {}
            }
        }
    }
}