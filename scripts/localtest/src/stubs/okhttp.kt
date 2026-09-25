// Compile-only okhttp3 stubs: production code uses okhttp3.Request/Response etc.,
// but unit tests never execute HTTP.
package okhttp3

import java.io.IOException

class HttpUrl private constructor() {
    class Builder {
        fun scheme(s: String) = this
        fun host(s: String) = this
        fun addPathSegment(s: String) = this
        fun addQueryParameter(k: String, v: String?) = this
        fun build(): HttpUrl = HttpUrl()
    }
    fun newBuilder(): Builder = Builder()
    companion object {
        @JvmStatic fun parse(url: String): HttpUrl? = HttpUrl()
        @JvmStatic fun get(url: String): HttpUrl = HttpUrl()
    }
    fun encodedPath(): String = "/"
    fun queryParameter(name: String): String? = null
}

class Headers private constructor() {
    class Builder {
        fun add(name: String, value: String) = this
        fun build(): Headers = Headers()
    }
    operator fun get(name: String): String? = null
    companion object { fun of(vararg pairs: String): Headers = Headers() }
}

class RequestBody private constructor()
fun String.toRequestBody(): RequestBody? = null

open class Request private constructor() {
    open class Builder {
        open fun url(u: HttpUrl) = this
        open fun url(u: String) = this
        open fun header(name: String, value: String) = this
        open fun addHeader(name: String, value: String) = this
        open fun headers(h: Headers) = this
        open fun get() = this
        open fun post(body: RequestBody?) = this
        open fun build(): Request = Request()
    }
    fun header(name: String): String? = null
    val url: HttpUrl get() = HttpUrl.get("")
}

class ResponseBody private constructor() {
    fun string(): String = ""
    fun close() {}
    fun contentLength(): Long = 0
}

open class Response private constructor() : java.io.Closeable {
    val isSuccessful: Boolean get() = true
    val code: Int get() = 200
    val body: ResponseBody? get() = null
    fun header(name: String): String? = null
    fun headers(name: String): List<String> = emptyList()
    override fun close() {}
}

interface Callback {
    fun onFailure(call: Call, e: IOException)
    fun onResponse(call: Call, response: Response)
}

open class Call(open val request: Request? = null) {
    @Throws(IOException::class) open fun execute(): Response = error("stub")
    open fun enqueue(cb: Callback) {}
    open fun cancel() {}
    open fun isCanceled(): Boolean = false
}

interface Interceptor { fun intercept(chain: Chain): Response }
interface Chain {
    fun request(): Request
    fun proceed(request: Request): Response
}

class OkHttpClient private constructor() {
    class Builder {
        fun connectTimeout(v: Long, u: java.util.concurrent.TimeUnit) = this
        fun readTimeout(v: Long, u: java.util.concurrent.TimeUnit) = this
        fun writeTimeout(v: Long, u: java.util.concurrent.TimeUnit) = this
        fun connectTimeout(v: Int, u: java.util.concurrent.TimeUnit) = this
        fun readTimeout(v: Int, u: java.util.concurrent.TimeUnit) = this
        fun writeTimeout(v: Int, u: java.util.concurrent.TimeUnit) = this
        fun callTimeout(v: Long, u: java.util.concurrent.TimeUnit) = this
        fun followRedirects(v: Boolean) = this
        fun followSslRedirects(v: Boolean) = this
        fun addInterceptor(i: Interceptor) = this
        fun build(): OkHttpClient = OkHttpClient()
    }
    fun newCall(request: Request): Call = Call(request)
}

// Stand-in for kotlinx-coroutines okhttp await; never executed in tests.
suspend fun Call.await(): Response {
    throw IOException("stub")
}
