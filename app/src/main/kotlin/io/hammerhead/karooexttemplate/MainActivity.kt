package io.hammerhead.karooexttemplate
import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    // ====== LE TUE CHIAVI STRAVA ======
    private val clientId = "190895"
    private val clientSecret = "827974f8301df4438afac3c05be00a4dfd723817"
    private val refreshToken = "31475ab564010a7acb01268571b6251b2ea05f78"
    // ==================================

    private val maxDescents = 15

    private data class Descent(
        val name: String,
        val lat: Double,
        val lng: Double,
        val kom: String,
        val lengthM: Int
    )

    private lateinit var output: TextView
    private val descents = ArrayList<Descent>()
    private var status = "Connessione a Strava..."
    private var lastLoc: Location? = null
    private var permissionAsked = false
    private var gpsStarted = false

    private val locationManager by lazy {
        getSystemService(LOCATION_SERVICE) as LocationManager
    }

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLoc = location
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        output = TextView(this).apply {
            textSize = 15f
            setPadding(24, 24, 24, 24)
        }
        scroll.addView(output)
        setContentView(scroll)
        render()

        Thread {
            status = try {
                loadDescents()
                "Caricati ${descents.size} segmenti in discesa."
            } catch (e: Exception) {
                "ERRORE Strava:\n${e.message}"
            }
            runOnUiThread { render() }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            startGps()
        } else if (!permissionAsked) {
            permissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    private fun startGps() {
        if (gpsStarted) return
        gpsStarted = true
        try {
            lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, listener
            )
        } catch (e: Exception) {
            status = "ERRORE GPS: ${e.message}"
        }
        render()
    }

    override fun onDestroy() {
        try { locationManager.removeUpdates(listener) } catch (e: Exception) { }
        super.onDestroy()
    }

    private fun render() {
        val sb = StringBuilder()
        sb.append(status).append("\n\n")
        val loc = lastLoc
        if (loc == null) {
            sb.append("Attendo il GPS del Karoo...\n(meglio all'aperto o vicino a una finestra)")
        } else {
            sb.append("GPS: ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}\n\n")
            if (descents.isEmpty()) {
                sb.append("Segmenti non ancora caricati.")
            } else {
                var nearest: Descent? = null
                var best = Double.MAX_VALUE
                for (d in descents) {
                    val dist = haversine(loc.latitude, loc.longitude, d.lat, d.lng)
                    if (dist < best) { best = dist; nearest = d }
                }
                val n = nearest
                if (n != null) {
                    sb.append("Discesa più vicina:\n")
                    sb.append("• ${n.name}\n")
                    sb.append("   partenza a ${best.toInt()} m\n")
                    sb.append("   KOM ${n.kom} · lunghezza ${n.lengthM} m\n\n")
                    sb.append("Muoviti: se la distanza cambia, il GPS live funziona ✅")
                }
            }
        }
        output.text = sb.toString()
    }

    private fun loadDescents() {
        val token = getAccessToken()
        var page = 1
        loop@ while (true) {
            val arr = JSONArray(apiGet("/segments/starred?per_page=100&page=$page", token))
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                if (descents.size >= maxDescents) break@loop
                val seg = arr.getJSONObject(i)
                if (seg.optDouble("average_grade", 0.0) >= 0) continue
                val start = seg.optJSONArray("start_latlng") ?: continue
                if (start.length() < 2) continue
                val id = seg.getLong("id")
                val name = seg.optString("name", "(senza nome)")
                val lengthM = seg.optDouble("distance", 0.0).toInt()
                val detail = JSONObject(apiGet("/segments/$id", token))
                val kom = detail.optJSONObject("xoms")?.optString("kom")?.ifBlank { null } ?: "n/d"
                descents.add(Descent(name, start.getDouble(0), start.getDouble(1), kom, lengthM))
                Thread.sleep(200)
            }
            if (arr.length() < 100) break
            page++
        }
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

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }
}
