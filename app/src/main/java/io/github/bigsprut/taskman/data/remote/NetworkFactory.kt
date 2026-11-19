package io.github.bigsprut.taskman.data.remote

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.Cache
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.create

/**
 * Сетевой слой приложения.
 *
 * Сознательные решения:
 * - **OkHttp `Cache`** включён: `/api/state` отдаёт `ETag`, поэтому повторный запрос без
 *   изменений получает `304` и тело из кэша — 0 байт трафика (§4). Gzip разжимает OkHttp.
 * - **Адрес сервера подставляет интерсептор**, Retrofit создаётся на заглушке: адрес
 *   вводится пользователем и может измениться без перезапуска (§5.4).
 * - **Два клиента.** API-клиент добавляет заголовки `Token`/`X-Device-Id`; клиент скачивания
 *   файлов их не добавляет — у `/api/attachments/{id}` токен передаётся только query-параметром
 *   (§4), ссылка абсолютная и уже содержит токен.
 * - **HTTP-логирование не включается**: заголовок `Token` не должен попасть ни в logcat,
 *   ни в crash-дамп (§5.4, §7).
 *
 * @param cacheDirectory каталог кэша (в приложении — `context.cacheDir`, в тестах — temp).
 */
class NetworkFactory(
    cacheDirectory: File,
    connection: ConnectionProvider,
) {

    private val cache: Cache = Cache(File(cacheDirectory, HTTP_CACHE_DIRECTORY), HTTP_CACHE_BYTES)

    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .addInterceptor(ConnectionInterceptor(connection))
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * Клиент для файлов по абсолютным ссылкам с токеном в query: **без** интерсептора
     * заголовков, иначе он подменил бы хост и добавил `Token` заголовком (эндпоинту это
     * не нужно, а подмена пути сломала бы уже собранный URL).
     */
    private val downloadClient: OkHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val api: TaskmanApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(apiClient)
        .addConverterFactory(KotlinxJsonConverterFactory(TaskmanJson))
        .build()
        .create(TaskmanApi::class.java)

    /** API-клиент (для тестов и интеграций). */
    fun okHttpClient(): OkHttpClient = apiClient

    /**
     * Клиент без интерсептора: используется для `GET /api/attachments/{id}?token=…` и Coil.
     * Кэш у него общий с API-клиентом.
     */
    fun fileClient(): OkHttpClient = downloadClient

    companion object {
        /** Заглушка: реальный адрес приходит из настроек через [ConnectionInterceptor]. */
        const val PLACEHOLDER_BASE_URL: String = "http://127.0.0.1/"

        private const val HTTP_CACHE_DIRECTORY = "http"
        private const val HTTP_CACHE_BYTES = 20L * 1024 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
    }
}
