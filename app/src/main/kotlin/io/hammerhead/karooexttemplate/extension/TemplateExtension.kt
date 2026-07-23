package io.hammerhead.karooexttemplate.extension
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import org.json.JSONArray

class TemplateExtension : KarooExtension("template-id", "1.0") {
    override val types by lazy {
        listOf(DescentDistanceType(applicationContext, extension))
    }
}

class DescentDistanceType(
    private val context: Context,
    extension: String
) : DataTypeImpl(extension, "descent-distance") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val descents = readDescents()

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                var best = -1.0
                for (d in descents) {
                    val dist = haversine(location.latitude, location.longitude, d[0], d[1])
                    if (best < 0 || dist < best) best = dist
                }
                if (best < 0) best = 0.0
                emitter.onNext(
                    StreamState.Streaming(
                        DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to best))
                    )
                )
            }
        }

        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
        } catch (e: Exception) {
            emitter.onNext(StreamState.NotAvailable)
        }

        emitter.setCancellable {
            try { lm.removeUpdates(listener) } catch (e: Exception) { }
        }
    }

    private fun readDescents(): List<DoubleArray> {
        val prefs = context.getSharedPreferences("karoo_discesa", Context.MODE_PRIVATE)
        val raw = prefs.getString("descents", null) ?: return emptyList()
        val out = ArrayList<DoubleArray>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(doubleArrayOf(o.getDouble("lat"), o.getDouble("lng")))
            }
        } catch (e: Exception) { }
        return out
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
