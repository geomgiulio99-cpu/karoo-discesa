package io.hammerhead.karooexttemplate
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.OnLocationChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    // ====== LE TUE CHIAVI STRAVA ======
    private val clientId = "190895"
    private val clientSecret = "827974f8301df4438afac3c05be00a4dfd723817"
    private val refreshToken = "31475ab564010a7acb01268571b6251b2ea05f78"
    // ===================================

    private val maxDescents = 15

    private lateinit var liveView: TextView
    private lateinit var output: TextView
    private lateinit var karooSystem: KarooSystemService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        liveView = TextView(this).apply {
            textSize = 16f
            setPadding(24, 24, 24, 12)
            text = "GPS: in attesa del segnale..."
        }
        val scroll = ScrollView(this)
        output = TextView(this).apply {
            textSize = 14f
            setPadding(24, 12, 24, 24)
            text = "Carico i segmenti..."
        }
        scroll.addView(output)
        root.addView(liveView)
        root.addView(scroll)
        setContentView(root)

        // 1) GPS dal vivo tramite il sistema Karoo
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { connected ->
            if (connected) {
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        karooSystem.consumerFlow<OnLocationChanged>().collect { loc ->
                            liveView.text = "GPS attivo ✅  lat ${"%.5f".format(loc.lat)}, lon ${"%.5f".format(loc.lng)}"
                        }
                    } catch (e: Exception) {
                        liveView.text = "GPS errore: ${e.message}"
                    }
                }
            } else {
                liveView.text = "Karoo non connesso al sistema"
            }
        }

        // 2) Lista dei segmenti preferiti in discesa (come prima)
        Thread {
            val result = try { loadSegments() } catch (e: Exception) { "ERRORE lista:\n${e.message}" }
            runOnUiThread { output.text = result }
        }.start()
    }

    private fun loadSegments(): String {
        val token = getAccessToken()
        val descents = ArrayList<JSONObject>()
        var page = 1
        while (true) {
            val arr = JSONArray(apiGet("/segments/starred?per_page=100&page=$page", token))
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val seg = arr.getJSONObject(i)
                if (seg.optDouble("average_grade", 0.0) < 0) descents.add(seg)
            }
            if (arr.length() < 100) break
            page++
        }
        if (descents.isEmpty()) return "Nessun segmento in discesa tra i preferiti."

        val sb = StringBuilder("Segmenti in discesa (primi ${minOf(descents.size, maxDescents)}):\n\n")
        var count = 0
        for (seg in descents) {
            if (count >= maxDescents) break
            count++
            val id = seg.getLong("id")
            val name = seg.optString("name", "(senza nome)")
            val kom = JSONObject(apiGet("/segments/$id", token))
                .optJSONObject("xoms")?.optString("kom")?.ifBlank { null } ?: "n/d"
            sb.append("• $name — KOM $kom\n")
            Thread.sleep(200)
        }
        return sb.toString()
    }

    private fun getAccessToken(): String {
        val conn = (URL("https://www.strava.com/oauth/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = "client_id=$clientId&client_secret=$clientSecret&refresh_token=$refreshToken&grant_type=refresh_token"
        conn.outputStream.use { it.write(body.toByteArray()) }
        return JSONObject(readResponse(conn)).getString("access_token")
    }

    private fun apiGet(endpoint: String, token: String): String {
        val conn = (URL("https://www.strava.com/api/v3$endpoint").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 15000
            setRequestProperty("Authorization", "Bearer $token")
        }
        return readResponse(conn)
    }

    private fun readResponse(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP $code su ${conn.url}\n$text")
        return text
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::karooSystem.isInitialized) karooSystem.disconnect()
    }
}
