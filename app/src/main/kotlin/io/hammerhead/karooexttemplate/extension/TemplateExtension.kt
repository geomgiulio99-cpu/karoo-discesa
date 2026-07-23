package io.hammerhead.karooexttemplate.extension
import android.content.Context
import android.graphics.Color
import android.widget.RemoteViews
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.MapEffect
import io.hammerhead.karooext.models.OnLocationChanged
import io.hammerhead.karooext.models.PlayBeepPattern
import io.hammerhead.karooext.models.ShowPolyline
import io.hammerhead.karooext.models.ShowSymbols
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.Symbol
import io.hammerhead.karooext.models.ViewConfig
import io.hammerhead.karooexttemplate.R
import org.json.JSONArray

data class Descent(
    val name: String,
    val lat: Double,
    val lng: Double,
    val endLat: Double,
    val endLng: Double,
    val poly: String,
    val komSec: Double,
    val lengthM: Double
)

fun parseKom(s: String): Double {
    val parts = s.trim().split(":")
    return try {
        when (parts.size) {
            3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
            2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
            1 -> parts[0].toDouble()
            else -> 0.0
        }
    } catch (e: Exception) { 0.0 }
}

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

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
                    o.optString("poly", ""),
                    parseKom(o.optString("kom", "0")),
                    o.optDouble("len", 0.0)
                )
            )
        }
    } catch (e: Exception) { }
    return out
}

class Snapshot(
    val onSegment: Boolean,
    val delta: Double,
    val fraction: Double,
    val label: String,
    val distToStart: Double,
    val started: Boolean,
    val finished: Boolean
)

class LiveTracker(private val descents: List<Descent>) {
    private var active: Descent? = null
    private var startMs = 0L
    private var traveled = 0.0
    private var lastLat = 0.0
    private var lastLng = 0.0

    fun update(lat: Double, lng: Double): Snapshot {
        val a = active
        if (a == null) {
            var near: Descent? = null
            var best = -1.0
            for (d in descents) {
                val dist = haversine(lat, lng, d.lat, d.lng)
                if (best < 0 || dist < best) { best = dist; near = d }
            }
            val n = near
            if (n != null && best in 0.0..50.0 && n.komSec > 0 && n.lengthM > 0) {
                active = n
                startMs = System.currentTimeMillis()
                traveled = 0.0
                lastLat = lat
                lastLng = lng
                return Snapshot(true, 0.0, 0.0, n.name, 0.0, true, false)
            }
            return Snapshot(false, 0.0, 0.0, n?.name ?: "", if (best < 0) 0.0 else best, false, false)
        }
        traveled += haversine(lastLat, lastLng, lat, lng)
        lastLat = lat
        lastLng = lng
        val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
        var frac = traveled / a.lengthM
        if (frac < 0.0) frac = 0.0
        if (frac > 1.0) frac = 1.0
        val delta = elapsed - a.komSec * frac
        val toEnd = haversine(lat, lng, a.endLat, a.endLng)
        var finished = false
        if (frac > 0.8 && toEnd < 50.0) {
            active = null
            finished = true
        }
        return Snapshot(true, delta, frac, a.name, 0.0, false, finished)
    }
}

class TemplateExtension : KarooExtension("template-id", "1.0") {

    lateinit var karooSystem: KarooSystemService

    override val types by lazy {
        listOf(
            DescentDistanceType(this, extension),
            DescentDeltaType(this, extension),
            DescentLiveType(this, extension)
        )
    }

    override fun onCreate() {
        super.onCreate()
        karooSystem = KarooSystemService(applicationContext)
        karooSystem.connect { }
    }

    fun beep(vararg tones: Pair<Int?, Int>) {
        try {
            karooSystem.dispatch(
                PlayBeepPattern(tones.map { PlayBeepPattern.Tone(it.first, it.second) })
            )
        } catch (e: Exception) { }
    }

    override fun startMap(emitter: Emitter<MapEffect>) {
        val descents = readDescents(applicationContext)
        val symbols = ArrayList<Symbol>()
        for (i in descents.indices) {
            val d = descents[i]
            if (d.poly.isNotEmpty()) {
                emitter.onNext(ShowPolyline("discesa-$i", d.poly, 0xFFFF6600.toInt(), 8))
            }
            symbols.add(Symbol.POI("disc-start-$i", d.lat, d.lng, Symbol.POI.Types.SUMMIT, "INIZIO ${d.name}"))
            symbols.add(Symbol.POI("disc-end-$i", d.endLat, d.endLng, Symbol.POI.Types.CONTROL, "FINE ${d.name}"))
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
        if (descents.isEmpty()) {
            emitter.onNext(StreamState.NotAvailable)
            return
        }
        emitter.onNext(StreamState.Searching)
        val consumerId = ext.karooSystem.addConsumer { loc: OnLocationChanged ->
            var best = -1.0
            for (d in descents) {
                val dist = haversine(loc.lat, loc.lng, d.lat, d.lng)
                if (best < 0 || dist < best) best = dist
            }
            if (best >= 0) {
                emitter.onNext(
                    StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to best)))
                )
            }
        }
        emitter.setCancellable {
            try { ext.karooSystem.removeConsumer(consumerId) } catch (e: Exception) { }
        }
    }
}

class DescentDeltaType(
    private val ext: TemplateExtension,
    extension: String
) : DataTypeImpl(extension, "descent-delta") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val descents = readDescents(ext.applicationContext)
        if (descents.isEmpty()) {
            emitter.onNext(StreamState.NotAvailable)
            return
        }
        emitter.onNext(StreamState.Searching)
        val tracker = LiveTracker(descents)
        val consumerId = ext.karooSystem.addConsumer { loc: OnLocationChanged ->
            val s = tracker.update(loc.lat, loc.lng)
            if (s.onSegment) {
                emitter.onNext(
                    StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to s.delta)))
                )
            } else {
                emitter.onNext(StreamState.Searching)
            }
        }
        emitter.setCancellable {
            try { ext.karooSystem.removeConsumer(consumerId) } catch (e: Exception) { }
        }
    }
}

class DescentLiveType(
    private val ext: TemplateExtension,
    extension: String
) : DataTypeImpl(extension, "descent-live") {

    override fun startStream(emitter: Emitter<StreamState>) {
        val descents = readDescents(ext.applicationContext)
        if (descents.isEmpty()) {
            emitter.onNext(StreamState.NotAvailable)
            return
        }
        val tracker = LiveTracker(descents)
        val consumerId = ext.karooSystem.addConsumer { loc: OnLocationChanged ->
            val s = tracker.update(loc.lat, loc.lng)
            val v = if (s.onSegment) s.delta else s.distToStart
            emitter.onNext(
                StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to v)))
            )
        }
        emitter.setCancellable {
            try { ext.karooSystem.removeConsumer(consumerId) } catch (e: Exception) { }
        }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val descents = readDescents(ext.applicationContext)
        val tracker = LiveTracker(descents)

        fun render(big: String, sub: String, color: Int, progress: Int) {
            val rv = RemoteViews(context.packageName, R.layout.field_discesa)
            rv.setTextViewText(R.id.dv_value, big)
            rv.setTextColor(R.id.dv_value, color)
            rv.setTextViewText(R.id.dv_sub, sub)
            rv.setProgressBar(R.id.dv_bar, 100, progress, false)
            emitter.updateView(rv)
        }

        if (descents.isEmpty()) {
            render("--", "nessun segmento salvato", Color.WHITE, 0)
            return
        }
        render("--", "attendo GPS", Color.WHITE, 0)

        val consumerId = ext.karooSystem.addConsumer { loc: OnLocationChanged ->
            val s = tracker.update(loc.lat, loc.lng)
            if (s.started) ext.beep(1200 to 120, null to 60, 1600 to 220)
            if (s.finished) ext.beep(1600 to 150, null to 80, 1600 to 150, null to 80, 1900 to 400)

            if (s.onSegment) {
                val sign = if (s.delta <= 0) "" else "+"
                val txt = sign + String.format("%.1f", s.delta)
                val col = if (s.delta <= 0) Color.GREEN else Color.RED
                render(txt, s.label, col, (s.fraction * 100).toInt())
            } else {
                render("${s.distToStart.toInt()} m", s.label, Color.WHITE, 0)
            }
        }
        emitter.setCancellable {
            try { ext.karooSystem.removeConsumer(consumerId) } catch (e: Exception) { }
        }
    }
}
