package com.kitalonlabs.neogold.sync.research.api

import com.kitalonlabs.neogold.sync.research.api.models.CardBatchReceipt
import com.kitalonlabs.neogold.sync.research.api.models.CardBatchUpload
import com.kitalonlabs.neogold.sync.research.api.models.DeviceStatus
import com.kitalonlabs.neogold.sync.research.api.models.EnrollmentRequest
import com.kitalonlabs.neogold.sync.research.api.models.EnrollmentResponse
import com.kitalonlabs.neogold.sync.research.api.models.ErrorCode
import com.kitalonlabs.neogold.sync.research.api.models.Health
import com.kitalonlabs.neogold.sync.research.api.models.ImageReceipt
import com.kitalonlabs.neogold.sync.research.api.models.ParticipantReceipt
import com.kitalonlabs.neogold.sync.research.api.models.ParticipantUpload
import com.kitalonlabs.neogold.sync.research.api.models.Problem
import com.kitalonlabs.neogold.sync.research.api.models.SessionManifest
import com.kitalonlabs.neogold.sync.research.api.models.SessionStatus
import com.kitalonlabs.neogold.sync.research.api.models.TsbReceipt
import com.kitalonlabs.neogold.sync.research.api.models.TsbUpload
import com.kitalonlabs.neogold.sync.research.crypto.Base64Url
import com.kitalonlabs.neogold.sync.research.crypto.ClockManager
import com.kitalonlabs.neogold.sync.research.crypto.DeviceAssertionMinter
import com.kitalonlabs.neogold.sync.research.crypto.DeviceKeyManager
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream

class ResearchApiClient(
    private val okHttpClient: OkHttpClient,
    val clockManager: ClockManager = ClockManager(),
    private val keyManager: DeviceKeyManager,
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
) {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val imageMediaType = "image/jpeg".toMediaType()

    // 1. GET /health
    suspend fun getHealth(baseUrl: String): Health {
        val url = normalizeUrl(baseUrl, "/health")
        val request = Request.Builder()
            .url(url)
            .get()
            .build()
        val responseStr = executeCall(request)
        return json.decodeFromString(Health.serializer(), responseStr)
    }

    // 2. POST /enrollments
    suspend fun enrollDevice(
        baseUrl: String,
        idempotencyKey: String,
        appVersion: String,
        enrollmentRequest: EnrollmentRequest
    ): EnrollmentResponse {
        val url = normalizeUrl(baseUrl, "/enrollments")
        val bodyBytes = json.encodeToString(EnrollmentRequest.serializer(), enrollmentRequest).toByteArray(Charsets.UTF_8)
        val request = Request.Builder()
            .url(url)
            .post(bodyBytes.toRequestBody(jsonMediaType))
            .header("Idempotency-Key", idempotencyKey)
            .header("X-NeoGold-App-Version", appVersion)
            .build()
        val responseStr = executeCall(request)
        val response = json.decodeFromString(EnrollmentResponse.serializer(), responseStr)
        clockManager.updateOffsetFromServerTime(response.server_time)
        return response
    }

    // 3. GET /device
    suspend fun getDevice(
        baseUrl: String,
        deviceId: String,
        keyAlias: String,
        appVersion: String
    ): DeviceStatus {
        val url = normalizeUrl(baseUrl, "/device")
        val responseStr = executeAuthenticatedCall(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            appVersion = appVersion,
            method = "GET",
            requestUrl = url,
            bodyBytes = null,
            imageSha256Hex = null,
            idempotencyKey = null
        )
        val status = json.decodeFromString(DeviceStatus.serializer(), responseStr)
        clockManager.updateOffsetFromServerTime(status.server_time)
        return status
    }

    // 4. DELETE /device
    suspend fun leaveStudy(
        baseUrl: String,
        deviceId: String,
        keyAlias: String,
        idempotencyKey: String,
        appVersion: String
    ) {
        val url = normalizeUrl(baseUrl, "/device")
        executeAuthenticatedCall(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            appVersion = appVersion,
            method = "DELETE",
            requestUrl = url,
            bodyBytes = null,
            imageSha256Hex = null,
            idempotencyKey = idempotencyKey
        )
    }

    // 5. PUT /card-batches/{batch_id}
    suspend fun putCardBatch(
        baseUrl: String,
        deviceId: String,
        keyAlias: String,
        batchId: String,
        idempotencyKey: String,
        appVersion: String,
        upload: CardBatchUpload
    ): CardBatchReceipt {
        val url = normalizeUrl(baseUrl, "/card-batches/$batchId")
        val bodyBytes = json.encodeToString(CardBatchUpload.serializer(), upload).toByteArray(Charsets.UTF_8)
        val responseStr = executeAuthenticatedCall(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            appVersion = appVersion,
            method = "PUT",
            requestUrl = url,
            bodyBytes = bodyBytes,
            imageSha256Hex = null,
            idempotencyKey = idempotencyKey
        )
        return json.decodeFromString(CardBatchReceipt.serializer(), responseStr)
    }

    // 6. PUT /participants/{participant_id}
    suspend fun putParticipant(
        baseUrl: String,
        deviceId: String,
        keyAlias: String,
        participantId: String,
        idempotencyKey: String,
        appVersion: String,
        upload: ParticipantUpload
    ): ParticipantReceipt {
        val url = normalizeUrl(baseUrl, "/participants/$participantId")
        val bodyBytes = json.encodeToString(ParticipantUpload.serializer(), upload).toByteArray(Charsets.UTF_8)
        val responseStr = executeAuthenticatedCall(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            appVersion = appVersion,
            method = "PUT",
            requestUrl = url,
            bodyBytes = bodyBytes,
            imageSha256Hex = null,
            idempotencyKey = idempotencyKey
        )
        return json.decodeFromString(ParticipantReceipt.serializer(), responseStr)
    }

    // 7. PUT /sessions/{session_id}
    suspend fun putSessionManifest(
        baseUrl: String,
        deviceId: String,
        keyAlias: String,
        sessionId: String,
        idempotencyKey: String,
        appVersion: String,
        manifest: SessionManifest
    ): SessionStatus {
        val url = normalizeUrl(baseUrl, "/sessions/$sessionId")
        val bodyBytes = json.encodeToString(SessionManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
        val responseStr = executeAuthenticatedCall(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            appVersion = appVersion,
            method = "PUT",
            requestUrl = url,
            bodyBytes = bodyBytes,
            imageSha256Hex = null,
            idempotencyKey = idempotencyKey
        )
        return json.decodeFromString(SessionStatus.serializer(), responseStr)
    }

    // 8. PUT /sessions/{session_id}/captures/{capture_id}/image
    suspend fun putCaptureImage(
        baseUrl: String,
        deviceId: String,
        keyAlias: String,
        sessionId: String,
        captureId: String,
        idempotencyKey: String,
        appVersion: String,
        imageSha256Hex: String,
        imageStreamProvider: () -> InputStream,
        imageSizeBytes: Long
    ): ImageReceipt {
        val url = normalizeUrl(baseUrl, "/sessions/$sessionId/captures/$captureId/image")
        val rawDigest = Base64Url.hexToBytes(imageSha256Hex)
        val digestHeader = "sha-256=:${Base64Url.encodeStandard(rawDigest)}:"

        val responseStr = executeAuthenticatedImageCall(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            appVersion = appVersion,
            requestUrl = url,
            idempotencyKey = idempotencyKey,
            digestHeader = digestHeader,
            imageSha256Hex = imageSha256Hex,
            imageStreamProvider = imageStreamProvider,
            imageSizeBytes = imageSizeBytes
        )
        return json.decodeFromString(ImageReceipt.serializer(), responseStr)
    }

    // 9. GET /sessions/{session_id}
    suspend fun getSessionStatus(
        baseUrl: String,
        deviceId: String,
        keyAlias: String,
        sessionId: String,
        appVersion: String
    ): SessionStatus {
        val url = normalizeUrl(baseUrl, "/sessions/$sessionId")
        val responseStr = executeAuthenticatedCall(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            appVersion = appVersion,
            method = "GET",
            requestUrl = url,
            bodyBytes = null,
            imageSha256Hex = null,
            idempotencyKey = null
        )
        return json.decodeFromString(SessionStatus.serializer(), responseStr)
    }

    // 10. PUT /tsb-measurements/{tsb_id}
    suspend fun putTsbMeasurement(
        baseUrl: String,
        deviceId: String,
        keyAlias: String,
        tsbId: String,
        idempotencyKey: String,
        appVersion: String,
        upload: TsbUpload
    ): TsbReceipt {
        val url = normalizeUrl(baseUrl, "/tsb-measurements/$tsbId")
        val bodyBytes = json.encodeToString(TsbUpload.serializer(), upload).toByteArray(Charsets.UTF_8)
        val responseStr = executeAuthenticatedCall(
            baseUrl = baseUrl,
            deviceId = deviceId,
            keyAlias = keyAlias,
            appVersion = appVersion,
            method = "PUT",
            requestUrl = url,
            bodyBytes = bodyBytes,
            imageSha256Hex = null,
            idempotencyKey = idempotencyKey
        )
        return json.decodeFromString(TsbReceipt.serializer(), responseStr)
    }

    private fun normalizeUrl(baseUrl: String, path: String): String {
        val base = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        return if (base.endsWith("/v1")) {
            "$base/$cleanPath"
        } else {
            "$base/v1/$cleanPath"
        }
    }

    private fun executeCall(request: Request): String {
        val response = okHttpClient.newCall(request).execute()
        val bodyStr = response.body?.string() ?: ""
        if (!response.isSuccessful) {
            handleErrorResponse(response, bodyStr)
        }
        return bodyStr
    }

    private fun executeAuthenticatedCall(
        baseUrl: String,
        deviceId: String,
        keyAlias: String,
        appVersion: String,
        method: String,
        requestUrl: String,
        bodyBytes: ByteArray?,
        imageSha256Hex: String?,
        idempotencyKey: String?,
        isRetry: Boolean = false
    ): String {
        val token = DeviceAssertionMinter.mintToken(
            deviceId = deviceId,
            keyAlias = keyAlias,
            baseAudUrl = baseUrl,
            httpMethod = method,
            requestUrl = requestUrl,
            bodyBytes = bodyBytes,
            imageSha256Hex = imageSha256Hex,
            clockManager = clockManager,
            keyManager = keyManager
        )

        val reqBuilder = Request.Builder()
            .url(requestUrl)
            .header("Authorization", "Bearer $token")
            .header("X-NeoGold-App-Version", appVersion)

        if (idempotencyKey != null) {
            reqBuilder.header("Idempotency-Key", idempotencyKey)
        }

        when (method) {
            "GET" -> reqBuilder.get()
            "DELETE" -> reqBuilder.delete()
            "PUT" -> reqBuilder.put((bodyBytes ?: ByteArray(0)).toRequestBody(jsonMediaType))
            "POST" -> reqBuilder.post((bodyBytes ?: ByteArray(0)).toRequestBody(jsonMediaType))
        }

        val response = okHttpClient.newCall(reqBuilder.build()).execute()
        val bodyStr = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val problem = tryParseProblem(bodyStr, response.code)
            if (!isRetry && (problem.code == ErrorCode.CLOCK_SKEW || problem.code == ErrorCode.AUTH_EXPIRED)) {
                if (problem.server_time != null) {
                    clockManager.updateOffsetFromServerTime(problem.server_time)
                    return executeAuthenticatedCall(
                        baseUrl = baseUrl,
                        deviceId = deviceId,
                        keyAlias = keyAlias,
                        appVersion = appVersion,
                        method = method,
                        requestUrl = requestUrl,
                        bodyBytes = bodyBytes,
                        imageSha256Hex = imageSha256Hex,
                        idempotencyKey = idempotencyKey,
                        isRetry = true
                    )
                }
            } else if (!isRetry && problem.code == ErrorCode.AUTH_REPLAY) {
                return executeAuthenticatedCall(
                    baseUrl = baseUrl,
                    deviceId = deviceId,
                    keyAlias = keyAlias,
                    appVersion = appVersion,
                    method = method,
                    requestUrl = requestUrl,
                    bodyBytes = bodyBytes,
                    imageSha256Hex = imageSha256Hex,
                    idempotencyKey = idempotencyKey,
                    isRetry = true
                )
            }
            throw ApiException(response.code, problem, response.headers)
        }

        return bodyStr
    }

    private fun executeAuthenticatedImageCall(
        baseUrl: String,
        deviceId: String,
        keyAlias: String,
        appVersion: String,
        requestUrl: String,
        idempotencyKey: String,
        digestHeader: String,
        imageSha256Hex: String,
        imageStreamProvider: () -> InputStream,
        imageSizeBytes: Long,
        isRetry: Boolean = false
    ): String {
        val token = DeviceAssertionMinter.mintToken(
            deviceId = deviceId,
            keyAlias = keyAlias,
            baseAudUrl = baseUrl,
            httpMethod = "PUT",
            requestUrl = requestUrl,
            bodyBytes = null,
            imageSha256Hex = imageSha256Hex,
            clockManager = clockManager,
            keyManager = keyManager
        )

        val streamingRequestBody = object : RequestBody() {
            override fun contentType() = imageMediaType
            override fun contentLength() = -1L
            override fun writeTo(sink: BufferedSink) {
                imageStreamProvider().use { input ->
                    sink.writeAll(input.source())
                }
            }
        }

        val request = Request.Builder()
            .url(requestUrl)
            .put(streamingRequestBody)
            .header("Authorization", "Bearer $token")
            .header("X-NeoGold-App-Version", appVersion)
            .header("Idempotency-Key", idempotencyKey)
            .header("Content-Digest", digestHeader)
            .build()

        val response = okHttpClient.newCall(request).execute()
        val bodyStr = response.body?.string() ?: ""

        if (!response.isSuccessful) {
            val problem = tryParseProblem(bodyStr, response.code)
            if (!isRetry && (problem.code == ErrorCode.CLOCK_SKEW || problem.code == ErrorCode.AUTH_EXPIRED)) {
                if (problem.server_time != null) {
                    clockManager.updateOffsetFromServerTime(problem.server_time)
                    return executeAuthenticatedImageCall(
                        baseUrl = baseUrl,
                        deviceId = deviceId,
                        keyAlias = keyAlias,
                        appVersion = appVersion,
                        requestUrl = requestUrl,
                        idempotencyKey = idempotencyKey,
                        digestHeader = digestHeader,
                        imageSha256Hex = imageSha256Hex,
                        imageStreamProvider = imageStreamProvider,
                        imageSizeBytes = imageSizeBytes,
                        isRetry = true
                    )
                }
            } else if (!isRetry && problem.code == ErrorCode.AUTH_REPLAY) {
                return executeAuthenticatedImageCall(
                    baseUrl = baseUrl,
                    deviceId = deviceId,
                    keyAlias = keyAlias,
                    appVersion = appVersion,
                    requestUrl = requestUrl,
                    idempotencyKey = idempotencyKey,
                    digestHeader = digestHeader,
                    imageSha256Hex = imageSha256Hex,
                    imageStreamProvider = imageStreamProvider,
                    imageSizeBytes = imageSizeBytes,
                    isRetry = true
                )
            }
            throw ApiException(response.code, problem, response.headers)
        }

        return bodyStr
    }

    private fun handleErrorResponse(response: Response, bodyStr: String) {
        val problem = tryParseProblem(bodyStr, response.code)
        throw ApiException(response.code, problem, response.headers)
    }

    private fun tryParseProblem(bodyStr: String, statusCode: Int): Problem {
        return try {
            json.decodeFromString(Problem.serializer(), bodyStr)
        } catch (_: Exception) {
            Problem(
                type = "https://neogold.example/problems/server-error",
                title = "HTTP $statusCode",
                status = statusCode,
                code = if (statusCode >= 500) ErrorCode.SERVER_ERROR else "HTTP_$statusCode",
                retryable = statusCode >= 500,
                request_id = "unknown",
                detail = bodyStr.take(200)
            )
        }
    }
}
