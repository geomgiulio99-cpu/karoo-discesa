package io.hammerhead.karooexttemplate
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    // ====== LE TUE CHIAVI STRAVA (se rigeneri il secret, cambialo qui) ======
    private val clientId = "190895"
    private val clientSecret = "827974f8301df4438afac3c05be00a4dfd723817"
    private val refreshToken = "31475ab564010a7acb01268571b6251b2ea05f78"
    // =======================================================================

    private val maxDescents = 15  // quante discese analizzare (per non superare i limiti API)

    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        output = TextView(this).apply {
            textSize = 15f
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(output)
        setContentView(scroll)
        output.text = "Connessione a Strava..."

        Thread {
            val result = try {
                loadSegments()
            } catch (e: Exception) {
                "ERRORE:\n${e.message}\n\n(copiami questo testo)"
            }
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

        if (descents.isEmpty())
            return "Nessun segmento in discesa tra i tuoi preferiti.\nAggiungine qualcuno con la stellina su Strava e riprova."

        val sb = StringBuilder()
        sb.append("Trovati ${descents.size} preferiti in discesa. Primi ${minOf(descents.size, maxDescents)} col KOM:\n\n")

        var count = 0
        for (seg in descents) {
            if (count >= maxDescents) break
            count++
            val id = seg.getLong("id")
            val name = seg.optString("name", "(senza nome)")
            val dist = seg.optDouble("distance", 0.0)
            val grade = seg.optDouble("average_grade", 0.0)

            val detail = JSONObject(apiGet("/segments/$id", token))
            val kom = detail.optJSONObject("xoms")?.optString("kom")?.ifBlank { null } ?: "n/d"

            sb.append("• $name\n")
            sb.append("   ${dist.toInt()} m · ${"%.1f".format(grade)}% · KOM $kom\n\n")
            Thread.sleep(200)
        }

        sb.append("\nSe qui sopra vedi i KOM, la lettura da Strava funziona ✅")
        return sb.toString()
    }

    private fun getAccessToken(): String {
        val conn = (URL("https://www.strava.com/oauth/token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        val body = "client_id=$clientId&client_secret=$clientSecret&refresh_token=$refreshToken&grant_type=refresh_token"
        conn.outputStream.use { it.write(body.toByteArray()) }
        return JSONObject(readResponse(conn)).getString("access_token")
    }

    private fun apiGet(endpoint: String, token: String): String {
        val conn = (URL("https://www.strava.com/api/v3$endpoint").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
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
}
