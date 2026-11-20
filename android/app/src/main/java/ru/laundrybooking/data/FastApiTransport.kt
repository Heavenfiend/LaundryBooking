package ru.laundrybooking.data

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.BufferedSink
import ru.laundrybooking.domain.ApiHttpError
import ru.laundrybooking.domain.ApiUnavailable
import ru.laundrybooking.domain.InvalidApiResponse

internal class FastApiTransport(apiUrl: String) {
    private val base = apiUrl.toHttpUrl()
    private val client = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    // Запись после паузы в интерфейсе не должна использовать простаивавшее соединение.
    private val writeClient = client.newBuilder()
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()
    val format = Json { ignoreUnknownKeys = true }

    suspend inline fun <reified T> json(
        path: List<String>,
        token: String? = null,
        method: String = "GET",
        payload: String? = null,
    ): T = request(path, token, method, payload) { format.decodeFromString<T>(it.string()) }

    suspend fun <T> request(
        path: List<String>,
        token: String?,
        method: String = "GET",
        payload: String? = null,
        decode: (ResponseBody) -> T,
    ): T {
        val url = base.newBuilder().addPathSegment("api").apply {
            path.forEach { addPathSegment(it) }
        }.build()
        val body = if (method == "POST") {
            val content = payload?.toRequestBody(JSON_TYPE) ?: ByteArray(0).toRequestBody()
            SingleUseBody(content)
        } else {
            null
        }
        val request = Request.Builder().url(url).method(method, body).apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        val transport = if (method == "GET") client else writeClient

        try {
            return suspendCancellableCoroutine { continuation ->
                val call = transport.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resumeWith(runCatching {
                            response.use {
                                if (!it.isSuccessful) {
                                    throw ApiHttpError(it.code, errorMessage(it.code, it.body?.string()))
                                }
                                decode(it.body ?: throw InvalidApiResponse())
                            }
                        })
                    }
                })
            }
        } catch (e: IOException) {
            throw ApiUnavailable(e)
        } catch (e: SerializationException) {
            throw InvalidApiResponse(e)
        }
    }

    private fun errorMessage(status: Int, body: String?): String {
        if (status >= 500) return "Сервер временно недоступен. Попробуйте ещё раз."
        val detail = body?.let {
            (runCatching { format.parseToJsonElement(it) }.getOrNull() as? JsonObject)?.get("detail")
        }
        return when (detail) {
            is JsonPrimitive -> detail.contentOrNull
            is JsonArray -> detail.mapNotNull {
                ((it as? JsonObject)?.get("msg") as? JsonPrimitive)?.contentOrNull
            }.joinToString(". ").ifBlank { null }
            else -> null
        } ?: "Сервер не выполнил запрос (HTTP $status)."
    }

    private companion object {
        val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private class SingleUseBody(private val content: RequestBody) : RequestBody() {
        // Повторная отправка POST после обрыва может создать ещё одну запись.
        override fun isOneShot() = true
        override fun contentType() = content.contentType()
        override fun contentLength() = content.contentLength()
        override fun writeTo(sink: BufferedSink) = content.writeTo(sink)
    }
}
