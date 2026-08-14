package com.example.watcher.data.remote

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.PartMap
import retrofit2.http.Path

interface DoubaoApiService {
    @POST("api/v3/responses")
    suspend fun analyzeIntent(
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: DoubaoRequest
    ): DoubaoResponse

    @POST("api/v3/responses")
    suspend fun analyzeImage(
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: DoubaoImageRequest
    ): DoubaoResponse

    @POST("api/v3/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: DoubaoChatCompletionRequest
    ): DoubaoChatCompletionResponse

    @Multipart
    @POST("api/v3/files")
    suspend fun uploadFile(
        @Header("Authorization") authorization: String,
        @Part("purpose") purpose: RequestBody,
        @PartMap preprocessConfigs: Map<String, @JvmSuppressWildcards RequestBody>,
        @Part file: MultipartBody.Part
    ): ArkFileResponse

    @Multipart
    @POST("api/v3/files")
    suspend fun uploadAudioFile(
        @Header("Authorization") authorization: String,
        @Part("purpose") purpose: RequestBody,
        @Part file: MultipartBody.Part
    ): ArkFileResponse

    @GET("api/v3/files/{fileId}")
    suspend fun getFile(
        @Header("Authorization") authorization: String,
        @Path("fileId") fileId: String
    ): ArkFileResponse

    @POST("api/v3/responses")
    suspend fun analyzeVideo(
        @Header("Authorization") authorization: String,
        @Header("Content-Type") contentType: String = "application/json",
        @Body request: DoubaoVideoRequest
    ): DoubaoResponse
}

data class DoubaoRequest(
    val model: String,
    val input: List<Message>,
    val stream: Boolean? = null,
    @SerializedName("response_format") val responseFormat: ResponseFormat? = null,
    val thinking: Thinking? = null
)

data class Message(
    val role: String,
    val content: List<ContentItem>
)

data class ContentItem(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: String? = null,
    @SerializedName("data") val data: String? = null,
    @SerializedName("mime_type") val mimeType: String? = null
)

data class DoubaoImageRequest(
    val model: String,
    val input: List<ImageMessage>,
    val stream: Boolean? = null,
    @SerializedName("response_format") val responseFormat: ResponseFormat? = null,
    val thinking: Thinking? = null,
    @SerializedName("max_output_tokens") val maxOutputTokens: Int? = null,
    val temperature: Double? = null
)

data class ImageMessage(
    val role: String,
    val content: List<ImageContentItem>
)

data class ImageContentItem(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: String? = null,
    val detail: String? = null,
    @SerializedName("image_pixel_limit") val imagePixelLimit: ImagePixelLimit? = null
)

data class ImagePixelLimit(
    @SerializedName("max_pixels") val maxPixels: Int,
    @SerializedName("min_pixels") val minPixels: Int
)

data class DoubaoChatCompletionRequest(
    val model: String,
    val messages: List<DoubaoChatMessage>,
    val stream: Boolean? = null,
    @SerializedName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    val thinking: Thinking? = null
)

data class DoubaoChatMessage(
    val role: String,
    val content: List<DoubaoChatContentItem>
)

data class DoubaoChatContentItem(
    val type: String,
    val text: String? = null,
    @SerializedName("image_url") val imageUrl: DoubaoChatImageUrl? = null
)

data class DoubaoChatImageUrl(
    val url: String,
    val detail: String? = null,
    @SerializedName("image_pixel_limit") val imagePixelLimit: ImagePixelLimit? = null
)

data class DoubaoVideoRequest(
    val model: String,
    val input: List<VideoMessage>,
    val stream: Boolean? = null,
    @SerializedName("response_format") val responseFormat: ResponseFormat? = null,
    val thinking: Thinking? = null
)

data class ResponseFormat(
    val type: String,
    @SerializedName("json_schema") val jsonSchema: JsonSchemaResponseFormat? = null
)

data class JsonSchemaResponseFormat(
    val name: String,
    val schema: Map<String, Any?>,
    val strict: Boolean = true
)

data class Thinking(
    val type: String
)

data class VideoMessage(
    val role: String,
    val content: List<VideoContentItem>
)

data class VideoContentItem(
    val type: String,
    val text: String? = null,
    @SerializedName("file_id") val fileId: String? = null,
    @SerializedName("audio_url") val audioUrl: String? = null
)

data class DoubaoResponse(
    val id: String? = null,
    val model: String? = null,
    @SerializedName("created_at") val createdAt: Long? = null,
    val output: List<OutputItem>? = null,
    val usage: UsageInfo? = null
)

data class DoubaoChatCompletionResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<DoubaoChatChoice>? = null,
    val usage: UsageInfo? = null
)

data class DoubaoChatChoice(
    val index: Int? = null,
    val message: DoubaoChatChoiceMessage? = null,
    @SerializedName("finish_reason") val finishReason: String? = null
)

data class DoubaoChatChoiceMessage(
    val role: String? = null,
    val content: String? = null
)

data class OutputItem(
    val type: String? = null,
    val role: String? = null,
    val content: List<OutputContent>? = null,
    val status: String? = null,
    val text: String? = null
)

data class OutputContent(
    val type: String? = null,
    val text: String? = null
)

data class UsageInfo(
    @SerializedName("input_tokens") val inputTokens: Int? = null,
    @SerializedName("output_tokens") val outputTokens: Int? = null,
    @SerializedName("total_tokens") val totalTokens: Int? = null
)

data class ArkFileResponse(
    val id: String? = null,
    @SerializedName("file_id") val fileId: String? = null,
    val filename: String? = null,
    val purpose: String? = null,
    val bytes: Long? = null,
    val status: String? = null
) {
    fun resolvedId(): String? = fileId ?: id
}

fun DoubaoResponse.extractOutputText(): String? {
    val candidates = buildList {
        output.orEmpty().forEach { item ->
            item.text?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::add)

            item.content.orEmpty().forEach { content ->
                content.text?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let(::add)
            }
        }
    }
    return candidates.lastOrNull()
}

fun DoubaoResponse.describeOutputShape(): String {
    val items = output.orEmpty().joinToString(separator = "; ") { item ->
        val contentTypes = item.content.orEmpty()
            .map { it.type ?: "unknown" }
            .joinToString(separator = ",")
            .ifBlank { "-" }
        "type=${item.type ?: "unknown"},status=${item.status ?: "-"},content=$contentTypes"
    }
    return "id=${id ?: "-"}, model=${model ?: "-"}, output=${if (items.isBlank()) "empty" else items}"
}
