package app.simpleguide.wiki

import app.simpleguide.BuildConfig
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Gemeinsamer HTTP-Zugang für Wikipedia, Nominatim und Overpass.
 *
 * Die Wikimedia-Server drosseln Programme ohne aussagekräftigen User-Agent sehr schnell (HTTP 429). Mit Name,
 * Version und Projektadresse im User-Agent, Beachtung von Retry-After und Pausen nach Fehlern gehen auch
 * Tausende Anfragen für eine ganze Stadt durch.
 */
object Http {
    const val USER_AGENT = "SimpleGuide/${BuildConfig.VERSION_NAME} (https://github.com/regard2462/SimpleGuide; offline city audio guide)"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build()) }
        .build()

    class HttpException(val code: Int, message: String) : IOException(message)

    /**
     * GET mit Wiederholung: bei 429 und 503 nach Retry-After (höchstens 60 s), bei Netzfehlern und 5xx mit
     * wachsender Pause. [busy] prüft den Antworttext auf "Server zu beschäftigt" (CirrusSearch) und wiederholt
     * dann ebenfalls.
     */
    suspend fun get(url: String, attempts: Int = 5, busy: (String) -> Boolean = { false }): String {
        var wait = 1500L
        var last: Exception? = null
        repeat(attempts) { attempt ->
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    when {
                        r.code == 429 || r.code == 503 -> {
                            val retry = r.header("Retry-After")?.toLongOrNull()?.times(1000) ?: wait
                            last = HttpException(r.code, "HTTP ${r.code}")
                            delay(retry.coerceIn(1000, 60_000))
                        }
                        r.code >= 500 -> { last = HttpException(r.code, "HTTP ${r.code}"); delay(wait) }
                        !r.isSuccessful -> throw HttpException(r.code, "HTTP ${r.code} for $url")
                        busy(body) && attempt < attempts - 1 -> { last = IOException("busy"); delay(wait) }
                        else -> return body
                    }
                }
            } catch (e: HttpException) {
                if (e.code in 400..499 && e.code != 429) throw e
                last = e
            } catch (e: IOException) {
                last = e
                delay(wait)
            }
            wait = (wait * 2).coerceAtMost(30_000)
        }
        throw last ?: IOException("request failed: $url")
    }
}
