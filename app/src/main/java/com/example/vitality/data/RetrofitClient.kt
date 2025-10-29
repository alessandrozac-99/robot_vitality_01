package com.example.vitality.data

import android.util.Base64
import android.util.Log
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object RetrofitClient {

    private const val BASE_URL = "https://api.wattsense.com/"

    // ⚠️ Usa ESATTAMENTE le stesse credenziali dell’app che funziona
    private const val API_KEY =
        "A38WTCxnSQ3xb6yrIDwP8E13Q6l0AFFOhBJrkV6e2xyIbXsGAO_LwcmTYqvWaVSr"
    private const val API_SECRET =
        "4Ff9aUmYXtxz1MAx8vmJ_dmyb0MESYA3I1En_gxCSiVtkPSJuV3vqRk8VD23UOsd7y9mHv5PhBTD9C4CHCz6rbd21XqmZRZ9"

    // singleton client + cache opzionale
    @Volatile private var httpClient: OkHttpClient? = null

    /** Espone l’OkHttpClient condiviso (stesso interceptor HMAC/timestamp di Retrofit). */
    fun okHttpClient(cacheDir: File? = null): OkHttpClient {
        val existing = httpClient
        if (existing != null) return existing
        return synchronized(this) {
            httpClient ?: buildOkHttp(cacheDir).also { httpClient = it }
        }
    }

    /** Crea l’API Retrofit usando **lo stesso** OkHttpClient singleton. */
    fun create(cacheDir: File? = null): WattsenseApi {
        val client = okHttpClient(cacheDir)
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WattsenseApi::class.java)
    }

    private fun buildOkHttp(cacheDir: File?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(SigningInterceptor(API_KEY, API_SECRET))
            .addInterceptor(HttpLoggingInterceptor { m -> Log.d("Retrofit", m) }.apply {
                level = HttpLoggingInterceptor.Level.BODY
            })

        cacheDir?.let {
            val cache = Cache(File(it, "http_cache"), 10L * 1024L * 1024L)
            builder.cache(cache)
        }
        return builder.build()
    }

    /**
     * Firma HMAC IDENTICA all’altra app:
     * message = METHOD + "\n" + encodedPath + ["\n" + encodedQuery] + ["\n" + body] + "\n" + TIMESTAMP(ms)
     * Headers: X-API-Auth: "<API_KEY>:<BASE64(HMAC-SHA512(message))>"
     *          X-API-Timestamp: "<epoch_millis>"
     */
    private class SigningInterceptor(
        private val apiKey: String,
        private val apiSecret: String,
        private val nowMs: () -> Long = { System.currentTimeMillis() } // millisecondi
    ) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val url = req.url

            val method = req.method.uppercase()
            val path = url.encodedPath
            val query = url.encodedQuery
            val bodyString = if (req.body != null && bodyAllowed(req.method)) {
                val buf = okio.Buffer()
                req.body!!.writeTo(buf)
                buf.readUtf8()
            } else null

            val timestamp = nowMs().toString()

            val sb = StringBuilder()
            sb.append(method).append('\n').append(path)
            if (!query.isNullOrEmpty()) sb.append('\n').append(query)
            if (!bodyString.isNullOrEmpty()) sb.append('\n').append(bodyString)
            sb.append('\n').append(timestamp)
            val message = sb.toString()

            val mac = Mac.getInstance("HmacSHA512")
            mac.init(SecretKeySpec(apiSecret.toByteArray(StandardCharsets.UTF_8), "HmacSHA512"))
            val raw = mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
            val signature = Base64.encodeToString(raw, Base64.NO_WRAP)

            val signed = req.newBuilder()
                .addHeader("X-API-Auth", "$apiKey:$signature")
                .addHeader("X-API-Timestamp", timestamp)
                .addHeader("Accept", "application/json")
                .build()

            val resp = chain.proceed(signed)
            if (resp.code == 401) {
                try {
                    val peek = resp.peekBody(Long.MAX_VALUE)
                    Log.e("Retrofit", "401 BODY: ${peek.string()}")
                } catch (_: Exception) {}
            }
            return resp
        }

        private fun bodyAllowed(method: String): Boolean =
            method.equals("POST", true) || method.equals("PUT", true) || method.equals("PATCH", true)
    }
}
