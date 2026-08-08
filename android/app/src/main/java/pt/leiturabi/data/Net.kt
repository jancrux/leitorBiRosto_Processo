package pt.leiturabi.data

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Cliente HTTP unico com endereco de servidor mutavel.
 *
 * O Retrofit e construido com um host de substituicao; um interceptor reescreve
 * cada pedido para o servidor configurado nas Definicoes, o que evita reconstruir
 * o Retrofit sempre que o utilizador muda de endereco (rede local <-> ngrok).
 */
object Net {

    private const val PLACEHOLDER = "http://leiturabi.local/"

    @Volatile
    var baseUrl: String = ""
        private set

    @Volatile
    var apiKey: String = ""
        private set

    fun configure(baseUrl: String, apiKey: String) {
        this.baseUrl = normalizeUrl(baseUrl)
        this.apiKey = apiKey.trim()
    }

    /** Aceita "192.168.1.10:8000" e devolve "http://192.168.1.10:8000/". */
    fun normalizeUrl(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            value = if (value.contains("ngrok") || value.contains(".app")) "https://$value" else "http://$value"
        }
        if (!value.endsWith("/")) value += "/"
        return value
    }

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        coerceInputValues = true
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val builder = original.newBuilder()

                if (apiKey.isNotEmpty()) builder.header("X-API-Key", apiKey)
                // Evita a pagina de aviso do ngrok gratuito em pedidos nao-browser.
                builder.header("ngrok-skip-browser-warning", "true")
                builder.header("User-Agent", "LeituraBi-Android/1.0")

                rewriteHost(original.url)?.let { builder.url(it) }
                chain.proceed(builder.build())
            }
            .build()
    }

    /** Reaponta o pedido para o servidor configurado, preservando caminho e query. */
    private fun rewriteHost(requestUrl: HttpUrl): HttpUrl? {
        val target = baseUrl.toHttpUrlOrNull() ?: return null
        if (requestUrl.host == target.host && requestUrl.port == target.port &&
            requestUrl.scheme == target.scheme && target.encodedPath == "/"
        ) return null

        val builder = target.newBuilder()
        requestUrl.pathSegments.filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        builder.encodedQuery(requestUrl.encodedQuery)
        return builder.build()
    }

    val service: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(PLACEHOLDER)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ApiService::class.java)
    }

    /** Converte um caminho relativo da API ("/api/photos/1/thumb") num URL completo. */
    fun absolute(path: String?): String {
        if (path.isNullOrBlank()) return ""
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val base = baseUrl.ifEmpty { return "" }
        return base.trimEnd('/') + "/" + path.trimStart('/')
    }
}
