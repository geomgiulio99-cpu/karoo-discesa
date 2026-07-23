package io.hammerhead.karooexttemplate.extension
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.StreamState
import org.json.JSONArray

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
        val descents = readDescents()

        // valore immediato: quanti segmenti sono in memoria (serve a capire se lo stream vive)
        emit(emitter, descents.size.toDouble())

        val consumerId = ext.karooSystem.addConsumer { loc: OnLocationChanged ->
            var best = -1.0
            for (d in descents) {
                val dist = haversine(loc.lat, loc.lng, d[0], d[1])
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

    private fun readDescents(): List<DoubleArray> {
        val prefs = ext.applicationContext
            .getSharedPreferences("karoo_discesa", android.content.Context.MODE_PRIVATE)
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
