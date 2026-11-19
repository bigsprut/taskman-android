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
 * - **HTTP-логирование не включается**: заголовок `Token` не должен попасть ни в logcat,
 *   ни в crash-дамп (§5.4, §7).
 *
 * @param cacheDirectory каталог кэша (в приложении — `context.cacheDir`, в тестах — temp).
 */
class NetworkFactory(
    cacheDirectory: File,
    connection: ConnectionProvider,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(cacheDirectory, HTTP_CACHE_DIRECTORY), HTTP_CACHE_BYTES))
        .addInterceptor(ConnectionInterceptor(connection))
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val api: TaskmanApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(KotlinxJsonConverterFactory(TaskmanJson))
        .build()
        .create(TaskmanApi::class.java)

    /** Для тестов и диагностики: состояние тела ответа и заголовки пойдут через этот клиент. */
    fun okHttpClient(): OkHttpClient = client

    companion object {
        /** Заглушка: реальный адрес приходит из настроек через [ConnectionInterceptor]. */
        const val PLACEHOLDER_BASE_URL: String = "http://127.0.0.1/"

        private const val HTTP_CACHE_DIRECTORY = "http"
        private const val HTTP_CACHE_BYTES = 20L * 1024 * 1024
        private const val CONNECT_TIMEOUT_SECONDS = 15L
        private const val READ_TIMEOUT_SECONDS = 30L
    }
}
