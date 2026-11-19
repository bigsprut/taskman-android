package io.github.bigsprut.taskman.data.remote

import java.lang.reflect.Type
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Converter
import retrofit2.Retrofit

/**
 * Конвертер Retrofit на kotlinx.serialization.
 *
 * Свой, а не `converter-kotlinx-serialization`, по двум причинам: (1) у проекта бюджет $0 и
 * лишняя зависимость не нужна, (2) конфигурация [Json] (строгий разбор, игнор чужих полей)
 * должна быть ровно одна на клиент — та же, что используется в тестах.
 */
class KotlinxJsonConverterFactory(private val json: Json) : Converter.Factory() {

    override fun responseBodyConverter(
        type: Type,
        annotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<ResponseBody, *> {
        val serializer: KSerializer<Any> = serializer(type)
        return Converter<ResponseBody, Any> { body ->
            body.use { json.decodeFromString(serializer, it.string()) }
        }
    }

    override fun requestBodyConverter(
        type: Type,
        parameterAnnotations: Array<out Annotation>,
        methodAnnotations: Array<out Annotation>,
        retrofit: Retrofit,
    ): Converter<*, RequestBody> {
        val serializer: KSerializer<Any> = serializer(type)
        return Converter<Any, RequestBody> { value ->
            json.encodeToString(serializer, value).toRequestBody(JSON_MEDIA_TYPE)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
