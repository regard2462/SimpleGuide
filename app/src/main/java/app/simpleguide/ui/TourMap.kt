package app.simpleguide.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.simpleguide.R
import app.simpleguide.data.Place
import app.simpleguide.geo.LatLon
import app.simpleguide.map.MapStyles
import app.simpleguide.tour.NearbyItem
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/**
 * Kleine Karte zur Orientierung: OpenFreeMap-Vektorkarte über MapLibre (wie in Vela Maps), darauf die Orte aus
 * der Wikipedia als Punkte mit Namen, der eigene Standort mit Gehrichtung und der Kreis, in dem erzählt wird.
 * Die Karte folgt dem Standort, bis man sie selbst verschiebt; der Knopf unten rechts holt sie zurück.
 * Ein Tipp auf einen Punkt wählt den Ort aus ([onSelect]).
 */
@Composable
fun TourMap(
    position: LatLon?,
    heading: Double?,
    accuracyM: Float?,
    items: List<NearbyItem>,
    speakingKey: String?,
    selectedKey: String?,
    radiusM: Int,
    onSelect: (Place?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = MapColors(
        place = MaterialTheme.colorScheme.primary.toArgb(),
        inRange = MaterialTheme.colorScheme.tertiary.toArgb(),
        speaking = MaterialTheme.colorScheme.secondary.toArgb(),
        heard = MaterialTheme.colorScheme.outline.toArgb(),
        me = MaterialTheme.colorScheme.primary.toArgb(),
        halo = if (dark) 0xFF111318.toInt() else 0xFFFFFFFF.toInt(),
        label = if (dark) 0xFFE3E2E6.toInt() else 0xFF1B1B1F.toInt(),
    )
    val mapView = remember { MapStyles.init(context); TouchMapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var follow by remember { mutableStateOf(true) }
    var centered by remember { mutableStateOf(false) }
    val currentItems by rememberUpdatedState(items)
    val currentSelect by rememberUpdatedState(onSelect)

    // ---- Lebenszyklus der MapView an den der Seite hängen
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    // ---- Karte und Stil (bei Wechsel hell/dunkel neu)
    LaunchedEffect(dark) {
        style = null
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isRotateGesturesEnabled = false   // Norden bleibt oben; die Gehrichtung zeigt der Pfeil
                isTiltGesturesEnabled = false
                isCompassEnabled = false
                isLogoEnabled = false              // Quellenangabe bleibt über das (i)
                setAttributionMargins(8, 0, 0, 8)
            }
            m.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) follow = false
            }
            m.addOnMapClickListener { ll ->
                val pt = m.projection.toScreenLocation(ll)
                val hit = m.queryRenderedFeatures(RectF(pt.x - 28, pt.y - 28, pt.x + 28, pt.y + 28), LAYER_DOTS).firstOrNull()
                val key = hit?.getStringProperty("key")
                currentSelect(currentItems.firstOrNull { it.place.key == key }?.place)
                true
            }
            m.setStyle(Style.Builder().fromUri(if (dark) MapStyles.DARK else MapStyles.LIGHT)) { st ->
                addLayers(st, colors)
                style = st
            }
        }
    }

    // ---- Kamera folgt dem Standort
    LaunchedEffect(map, position, follow) {
        val m = map ?: return@LaunchedEffect
        val p = position ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        val target = LatLng(p.lat, p.lon)
        if (!centered) {
            m.cameraPosition = CameraPosition.Builder().target(target).zoom(16.0).build()
            centered = true
        } else m.easeCamera(CameraUpdateFactory.newLatLng(target), 700)
    }

    // ---- Daten auf der Karte
    LaunchedEffect(style, items, speakingKey, selectedKey) {
        val st = style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>(SRC_PLACES)?.setGeoJson(FeatureCollection.fromFeatures(items.map { item ->
            Feature.fromGeometry(Point.fromLngLat(item.place.lon, item.place.lat)).apply {
                addStringProperty("key", item.place.key)
                addStringProperty("name", item.place.name)
                addStringProperty("state", when {
                    item.place.key == speakingKey -> "speaking"
                    item.place.key == selectedKey -> "selected"
                    item.heard -> "heard"
                    item.inRange -> "range"
                    else -> "place"
                })
            }
        }))
    }
    LaunchedEffect(style, position, heading, accuracyM, radiusM) {
        val st = style ?: return@LaunchedEffect
        val p = position ?: return@LaunchedEffect
        val me = Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
            addNumberProperty("bearing", heading ?: 0.0)
            addBooleanProperty("moving", heading != null)
        }
        st.getSourceAs<GeoJsonSource>(SRC_ME)?.setGeoJson(me)
        val areas = buildList {
            add(Feature.fromGeometry(circle(p, radiusM.toDouble())).apply { addStringProperty("kind", "radius") })
            accuracyM?.takeIf { it > 8 }?.let { add(Feature.fromGeometry(circle(p, it.toDouble())).apply { addStringProperty("kind", "accuracy") }) }
        }
        st.getSourceAs<GeoJsonSource>(SRC_AREAS)?.setGeoJson(FeatureCollection.fromFeatures(areas))
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
        if (!follow && position != null) {
            FilledTonalIconButton(
                onClick = { follow = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) { Icon(Icons.Default.MyLocation, stringResource(R.string.map_center)) }
        }
    }
}

private class MapColors(val place: Int, val inRange: Int, val speaking: Int, val heard: Int, val me: Int, val halo: Int, val label: Int)

private const val SRC_PLACES = "sg-places"
private const val SRC_ME = "sg-me"
private const val SRC_AREAS = "sg-areas"
private const val LAYER_DOTS = "sg-places-dots"
private const val ARROW = "sg-arrow"

private fun addLayers(st: Style, c: MapColors) {
    // Liberty zeichnet Gebäude als 3D-Blöcke; von oben ohne Neigung wirken sie unruhig, die flachen Umrisse genügen
    st.getLayer("building-3d")?.setProperties(PropertyFactory.visibility(Property.NONE))
    st.addSource(GeoJsonSource(SRC_AREAS))
    st.addSource(GeoJsonSource(SRC_PLACES))
    st.addSource(GeoJsonSource(SRC_ME))
    st.addImage(ARROW, arrowBitmap(c.me, c.halo))
    val kind = Expression.get("kind")
    st.addLayer(FillLayer("sg-areas-fill", SRC_AREAS).withProperties(
        PropertyFactory.fillColor(Expression.match(kind, Expression.color(c.inRange), Expression.stop("accuracy", Expression.color(c.me)))),
        PropertyFactory.fillOpacity(Expression.match(kind, Expression.literal(0.07f), Expression.stop("accuracy", Expression.literal(0.12f)))),
    ))
    st.addLayer(LineLayer("sg-areas-line", SRC_AREAS).withProperties(
        PropertyFactory.lineColor(c.inRange),
        PropertyFactory.lineOpacity(Expression.match(kind, Expression.literal(0.6f), Expression.stop("accuracy", Expression.literal(0f)))),
        PropertyFactory.lineWidth(1.5f),
        PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
    ))
    val state = Expression.get("state")
    st.addLayer(CircleLayer(LAYER_DOTS, SRC_PLACES).withProperties(
        PropertyFactory.circleRadius(Expression.match(state, Expression.literal(6f),
            Expression.stop("speaking", Expression.literal(10f)), Expression.stop("selected", Expression.literal(9f)))),
        PropertyFactory.circleColor(Expression.match(state, Expression.color(c.place),
            Expression.stop("speaking", Expression.color(c.speaking)), Expression.stop("selected", Expression.color(c.speaking)),
            Expression.stop("range", Expression.color(c.inRange)), Expression.stop("heard", Expression.color(c.heard)))),
        PropertyFactory.circleStrokeColor(c.halo),
        PropertyFactory.circleStrokeWidth(2f),
    ))
    st.addLayer(SymbolLayer("sg-places-labels", SRC_PLACES).withProperties(
        PropertyFactory.textField(Expression.get("name")),
        PropertyFactory.textFont(arrayOf(MapStyles.FONT)),
        PropertyFactory.textSize(12f),
        PropertyFactory.textColor(c.label),
        PropertyFactory.textHaloColor(c.halo),
        PropertyFactory.textHaloWidth(1.5f),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
        PropertyFactory.textOffset(arrayOf(0f, 0.9f)),
        PropertyFactory.textMaxWidth(8f),
        PropertyFactory.textOptional(true),
    ).apply { minZoom = 15f })
    st.addLayer(CircleLayer("sg-me-dot", SRC_ME).withProperties(
        PropertyFactory.circleRadius(7f),
        PropertyFactory.circleColor(c.me),
        PropertyFactory.circleStrokeColor(c.halo),
        PropertyFactory.circleStrokeWidth(2.5f),
    ))
    st.addLayer(SymbolLayer("sg-me-arrow", SRC_ME).withProperties(
        PropertyFactory.iconImage(ARROW),
        PropertyFactory.iconRotate(Expression.toNumber(Expression.get("bearing"))),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconOpacity(Expression.switchCase(Expression.get("moving"), Expression.literal(1f), Expression.literal(0f))),
    ))
}

/** Kreis aus 48 Punkten um [c] mit [radiusM] Metern (für Erzählradius und GPS-Genauigkeit). */
private fun circle(c: LatLon, radiusM: Double): Polygon {
    val dLat = radiusM / 111_195.0
    val dLon = radiusM / (111_195.0 * cos(Math.toRadians(c.lat)))
    val ring = (0..48).map { i ->
        val a = 2 * Math.PI * i / 48
        Point.fromLngLat(c.lon + dLon * sin(a), c.lat + dLat * cos(a))
    }
    return Polygon.fromLngLats(listOf(ring))
}

/** Pfeil für die Gehrichtung, vor dem Punkt in Laufrichtung. */
private fun arrowBitmap(color: Int, halo: Int): Bitmap {
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val path = Path().apply {
        moveTo(size / 2f, 4f)
        lineTo(size / 2f + 15f, 30f)
        lineTo(size / 2f, 24f)
        lineTo(size / 2f - 15f, 30f)
        close()
    }
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = halo; style = Paint.Style.STROKE; strokeWidth = 6f; strokeJoin = Paint.Join.ROUND })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL })
    return bmp
}

/**
 * MapView, die ihre Wischgesten selbst behält: In der scrollbaren Tour-Seite würde die Liste sonst das
 * Verschieben der Karte übernehmen.
 */
@SuppressLint("ViewConstructor")
private class TouchMapView(context: Context) : MapView(context) {
    init { onCreate(null) }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(ev)
    }
}
