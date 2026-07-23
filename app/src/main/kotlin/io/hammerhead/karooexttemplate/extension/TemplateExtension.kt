package io.hammerhead.karooexttemplate.extension
import android.content.Context
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import org.json.JSONArray

data class Descent(
    val name: String,
    val lat: Double,
    val lng: Double,
    val endLat: Double,
    val endLng: Double,
    val poly: String
)

fun readDescents(context: Context): List<Descent> {
    val prefs = context.getSharedPreferences("karoo_discesa", Context.MODE_PRIVATE)
    val raw = prefs.getString("descents", null) ?: return emptyList()
    val out = ArrayList<Descent>()
    try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val la = o.getDouble("lat")
            val ln = o.getDouble("lng")
            out.add(
                Descent(
                    o.optString("name", "?"),
                    la, ln,
                    o.optDouble("endLat", la),
                    o.optDouble("endLng", ln),
                    o.optString("poly", "")
                )
            )
        }
    } catch (e: Exception) { }
    return out
}

class TemplateExtension : KarooExtension("template-id", "1.0") {

    lateinit var karooSystem: KarooSystemService

    override val types by lazy {
        listOf(DescentDistanceType(this, extension))
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { }
    }

    override fun startMap(emitter: Emitter<MapEffect>) {
        val descents = readDescents(applicationContext)
        val symbols = ArrayList<Symbol>()
        for (i in descents.indices) {
            val d = descents[i]
            if (d.poly.isNotEmpty()) {
                emitter.onNext(ShowPolyline("discesa-$i", d.poly, 0xFFFF6600.toInt(), 8))
            }
            symbols.add(
                Symbol.POI("disc-start-$i", d.lat, d.lng, Symbol.POI.Types.SUMMIT, "INIZIO ${d.name}")
            )
            symbols.add(
                Symbol.POI("disc-end-$i", d.endLat, d.endLng, Symbol.POI.Types.CONTROL, "FINE ${d.name}")
            )
        }
        if (symbols.isNotEmpty()) emitter.onNext(ShowSymbols(symbols))
    }

    override fun onDestroy() {
        try { karooSystem.disconnect() } catch (e: Exception) { }
        super.onDestroy()
    }
}

class DescentDistanceType(
    private val ext: TemplateExtension,
    extension: String
) : DataTypeImpl(extension, "descent-distance") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val descents = readDescents(ext.applicationContext)
        emit(emitter, descents.size.toDouble())

        val consumerId = ext.karooSystem.addConsumer { loc: OnLocationChanged ->
            var best = -1.0
            for (d in descents) {
                val dist = haversine(loc.lat, loc.lng, d.lat, d.lng)
                if (best < 0 || dist < best) best = dist
            }
            emit(emitter, if (best < 0) 0.0 else best)
        }

        emitter.setCancellable {
            try { ext.karooSystem.removeConsumer(consumerId) } catch (e: Exception) { }
        }
    }

    private fun emit(emitter: Emitter<StreamState>, value: Double) {
        emitter.onNext(
            StreamState.Streaming(
                DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to value))
            )
        )
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
