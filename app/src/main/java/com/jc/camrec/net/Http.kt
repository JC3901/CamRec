package com.jc.camrec.net

import android.webkit.CookieManager
import android.webkit.WebSettings
import com.jc.camrec.App
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpException(val code: Int, message: String) : IOException(message)

/**
 * Shares cookies with the in-app browser, so a site check passed there
 * (Cloudflare, age gate, login) also applies to the app's own requests.
 */
private object WebViewCookieJar : CookieJar {
    private fun manager(): CookieManager? = try { CookieManager.getInstance() } catch (_: Throwable) { null }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val cm = manager() ?: return
        for (cookie in cookies) cm.setCookie(url.toString(), cookie.toString())
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = manager()?.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

object Http {
    /** Same user agent as the in-app browser, so browser-issued clearance cookies stay valid. */
    val userAgent: String by lazy {
        try { WebSettings.getDefaultUserAgent(App.ctx) } catch (_: Throwable) {
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
        }
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val req = chain.request()
                if (req.header("User-Agent") == null) {
                    chain.proceed(req.newBuilder().header("User-Agent", userAgent).build())
                } else chain.proceed(req)
            }
            .build()
    }

    private fun request(url: String, headers: Map<String, String>): Request {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    /** Blocking GET returning (code, body). Call from a background thread. */
    fun getRaw(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        client.newCall(request(url, headers)).execute().use { resp ->
            return resp.code to (resp.body?.string() ?: "")
        }
    }

    /** Blocking GET that throws on a non-2xx status. */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        client.newCall(request(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} from ${hostOf(url)}")
            return resp.body?.string() ?: ""
        }
    }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        client.newCall(request(url, headers)).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} from ${hostOf(url)}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }

    /** Blocking JSON POST returning (code, body). */
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val b = Request.Builder().url(url)
            .post(json.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { resp -> return resp.code to (resp.body?.string() ?: "") }
    }

    /** Adds query parameters (e.g. a playback key) that a URL is missing. */
    fun withParams(url: String, params: Map<String, String>): String {
        if (params.isEmpty()) return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        val b = parsed.newBuilder()
        params.forEach { (k, v) -> if (parsed.queryParameter(k) == null) b.addQueryParameter(k, v) }
        return b.build().toString()
    }

    suspend fun text(url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) { getText(url, headers) }

    fun resolve(base: String, relative: String): String =
        base.toHttpUrlOrNull()?.resolve(relative)?.toString() ?: relative

    fun hostOf(url: String): String = url.toHttpUrlOrNull()?.host ?: url
}
