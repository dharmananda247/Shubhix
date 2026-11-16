package com.shubhix.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * All internet traffic goes through here. If the phone's own DNS cannot find a server
 * (the "Unable to resolve host" problem), the app asks public DNS-over-HTTPS resolvers directly,
 * so users never have to change any DNS setting.
 */
object Net {
    private val cache = HashMap<String, List<InetAddress>>()

    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            try {
                return Dns.SYSTEM.lookup(hostname)
            } catch (e: UnknownHostException) {
                val cached = synchronized(cache) { cache[hostname] }
                if (cached != null) return cached
                val found = doh(hostname)
                if (found.isNotEmpty()) {
                    synchronized(cache) { cache[hostname] = found }
                    return found
                }
                throw e
            }
        }
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .dns(dns)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    /** Resolves a host through Google and Cloudflare DNS-over-HTTPS, using their IP addresses directly. */
    fun doh(host: String): List<InetAddress> {
        val endpoints = listOf(
            Pair("https://8.8.8.8/resolve?name=" + host + "&type=A", null as String?),
            Pair("https://1.1.1.1/dns-query?name=" + host + "&type=A", "application/dns-json" as String?)
        )
        for (ep in endpoints) {
            try {
                val r = dohQuery(ep.first, host, ep.second)
                if (r.isNotEmpty()) return r
            } catch (e: Exception) {
                // try the next resolver
            }
        }
        return emptyList()
    }

    private fun dohQuery(url: String, host: String, accept: String?): List<InetAddress> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 4000
        if (accept != null) c.setRequestProperty("accept", accept)
        val text = c.inputStream.bufferedReader().use { it.readText() }
        val answers = JSONObject(text).optJSONArray("Answer") ?: return emptyList()
        val out = ArrayList<InetAddress>()
        for (i in 0 until answers.length()) {
            val a = answers.getJSONObject(i)
            if (a.optInt("type") == 1) {
                val ip = InetAddress.getByName(a.getString("data"))   // numeric address: no lookup happens
                out.add(InetAddress.getByAddress(host, ip.address))
            }
        }
        return out
    }

    /** Calls the Gemini API. Returns (HTTP status, response text). Network problems throw IOException. */
    fun call(url: String, apiKey: String, method: String, body: JSONObject?): Pair<Int, String> {
        val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).header("x-goog-api-key", apiKey.trim()).method(method, rb).build()
        return client.newCall(req).execute().use { resp -> Pair(resp.code, resp.body?.string() ?: "") }
    }

    /** A plain GET for public pages such as the GitHub release check. */
    fun getPublic(url: String): Pair<Int, String> {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ShubhixAI")
            .build()
        return client.newCall(req).execute().use { resp -> Pair(resp.code, resp.body?.string() ?: "") }
    }
}

data class Check(val name: String, val ok: Boolean, val detail: String)

/** The "Connection check" button: tests each step and says what to fix. */
object Diagnostics {
    private const val HOST = "generativelanguage.googleapis.com"

    suspend fun run(ctx: Context, key: String, model: String): List<Check> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Check>()

        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val net = try {
            cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) { false }
        out.add(Check("Internet connection", net, if (net) "Connected." else "No network. Turn on mobile data or Wi-Fi."))

        val sysDns = try { InetAddress.getAllByName(HOST); true } catch (e: Exception) { false }
        out.add(Check("Phone DNS finds Google", sysDns,
            if (sysDns) "Working." else "Your phone's DNS cannot find Google. Shubhix AI uses its own fallback; to fix it for every app, set Private DNS to dns.google."))
        if (!sysDns) {
            val fb = try { doh(HOST).isNotEmpty() } catch (e: Exception) { false }
            out.add(Check("Built-in DNS fallback", fb, if (fb) "Working. Shubhix AI can still reach Google." else "Also blocked. Try another network or a VPN."))
        }

        if (key.isBlank()) {
            out.add(Check("API key", false, "No key entered yet."))
            return@withContext out
        }
        val models = try {
            Net.call("https://" + HOST + "/v1beta/models?pageSize=5", key, "GET", null)
        } catch (e: IOException) {
            out.add(Check("Reach Google", false, "Cannot connect (" + (e.message ?: "network error") + ")."))
            return@withContext out
        }
        out.add(Check("Reach Google", true, "Connected."))
        val keyOk = models.first in 200..299
        out.add(Check("API key", keyOk, if (keyOk) "Accepted." else GeminiClient.friendly(models.first, models.second)))
        if (!keyOk) return@withContext out

        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", "Reply with OK")))))
        val gen = try {
            Net.call("https://" + HOST + "/v1beta/models/" + model.trim() + ":generateContent", key, "POST", body)
        } catch (e: IOException) {
            out.add(Check("Model " + model, false, "Connection dropped while testing."))
            return@withContext out
        }
        val genOk = gen.first in 200..299
        out.add(Check("Model " + model, genOk, if (genOk) "Answering." else GeminiClient.friendly(gen.first, gen.second)))
        out
    }
}
