package app.simpleguide.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.simpleguide.geo.BBox
import app.simpleguide.geo.Geo
import app.simpleguide.geo.LatLon
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Alles Heruntergeladene in einer SQLite-Datei: Orte mit Einleitung und (komprimiertem) Volltext, die
 * Gebiete, für die schon online gesucht wurde, heruntergeladene Städte, gehörte Orte und Straßen.
 * Bewusst ohne ORM; die Abfragen sind wenige und einfach.
 */
class PlaceDb(context: Context) : SQLiteOpenHelper(context, "places.db", null, 2) {

    init {
        // WAL: Die Tour liest weiter, während ein Stadt-Download in langen Transaktionen schreibt
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE place(lang TEXT NOT NULL, pageid INTEGER NOT NULL, title TEXT NOT NULL, lat REAL NOT NULL,
               lon REAL NOT NULL, description TEXT, length INTEGER NOT NULL DEFAULT 0, intro TEXT,
               details INTEGER NOT NULL DEFAULT 0, full BLOB, updated INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(lang, pageid))"""
        )
        db.execSQL("CREATE INDEX place_geo ON place(lang, lat, lon)")
        db.execSQL(
            """CREATE TABLE city(id INTEGER PRIMARY KEY AUTOINCREMENT, lang TEXT NOT NULL, name TEXT NOT NULL,
               south REAL, west REAL, north REAL, east REAL, places INTEGER DEFAULT 0, texts INTEGER DEFAULT 0,
               streets INTEGER DEFAULT 0, bytes INTEGER DEFAULT 0, state TEXT DEFAULT 'queued', message TEXT DEFAULT '',
               with_texts INTEGER DEFAULT 1, with_streets INTEGER DEFAULT 1, with_map INTEGER DEFAULT 1, created INTEGER)"""
        )
        db.execSQL("CREATE TABLE city_place(city INTEGER NOT NULL, lang TEXT NOT NULL, pageid INTEGER NOT NULL, PRIMARY KEY(city, lang, pageid))")
        db.execSQL("CREATE TABLE area(lang TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, radius REAL NOT NULL, fetched INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE heard(lang TEXT NOT NULL, pageid INTEGER NOT NULL, at INTEGER NOT NULL, PRIMARY KEY(lang, pageid))")
        db.execSQL(
            """CREATE TABLE street(id INTEGER PRIMARY KEY AUTOINCREMENT, city INTEGER NOT NULL, name TEXT NOT NULL,
               s REAL NOT NULL, w REAL NOT NULL, n REAL NOT NULL, e REAL NOT NULL, pts BLOB NOT NULL)"""
        )
        db.execSQL("CREATE INDEX street_box ON street(s, n)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 2: Kartenausschnitt je Stadt (MapLibre-Offline-Region)
        if (oldVersion < 2) db.execSQL("ALTER TABLE city ADD COLUMN with_map INTEGER DEFAULT 1")
    }

    // ================================================================ Orte

    /** Neue Orte eintragen; vorhandene behalten ihre Texte, nur Titel und Lage werden aufgefrischt. */
    fun upsertBasic(lang: String, hits: List<GeoHit>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (h in hits) {
                val cv = ContentValues().apply {
                    put("lang", lang); put("pageid", h.pageId); put("title", h.title)
                    put("lat", h.lat); put("lon", h.lon); put("updated", now)
                }
                if (db.insertWithOnConflict("place", null, cv, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                    db.update("place", ContentValues().apply { put("title", h.title); put("lat", h.lat); put("lon", h.lon) },
                        "lang=? AND pageid=?", arrayOf(lang, h.pageId.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun updateDetails(lang: String, details: Map<Long, PageDetails>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, d) in details) {
                if (d.disambiguation || d.missing) { db.delete("place", "lang=? AND pageid=?", arrayOf(lang, id.toString())); continue }
                db.update("place", ContentValues().apply {
                    put("description", d.description); put("length", d.length); put("details", 1)
                }, "lang=? AND pageid=?", arrayOf(lang, id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun updateIntros(lang: String, intros: Map<Long, String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((id, text) in intros) {
                db.update("place", ContentValues().apply { put("intro", text) }, "lang=? AND pageid=?", arrayOf(lang, id.toString()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun updateFull(lang: String, pageId: Long, text: String) {
        writableDatabase.update("place", ContentValues().apply { put("full", gzip(text)) }, "lang=? AND pageid=?", arrayOf(lang, pageId.toString()))
    }

    fun full(lang: String, pageId: Long): String? =
        readableDatabase.rawQuery("SELECT full FROM place WHERE lang=? AND pageid=?", arrayOf(lang, pageId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) gunzip(c.getBlob(0)) else null
        }

    fun place(lang: String, pageId: Long): Place? =
        readableDatabase.rawQuery("SELECT $COLS FROM place WHERE lang=? AND pageid=?", arrayOf(lang, pageId.toString())).use { c ->
            if (c.moveToFirst()) row(c) else null
        }

    /**
     * Orte im Umkreis von [radiusM] um [p] (ohne Volltext), die nächsten zuerst. Höchstens [limit]; in sehr
     * dichten Innenstädten fallen so die fernsten weg, nicht beliebige.
     */
    fun near(lang: String, p: LatLon, radiusM: Double, limit: Int = 3000): List<Place> {
        val b = Geo.bbox(p, radiusM)
        // Abstand im Gradmaß genügt zum Sortieren; Längengrade sind um cos(lat) kürzer
        val k = Math.cos(Math.toRadians(p.lat))
        return readableDatabase.rawQuery(
            """SELECT $COLS FROM place WHERE lang=? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?
               ORDER BY (lat-?)*(lat-?) + (lon-?)*(lon-?)*? LIMIT $limit""",
            arrayOf(
                lang, b.south.toString(), b.north.toString(), b.west.toString(), b.east.toString(),
                p.lat.toString(), p.lat.toString(), p.lon.toString(), p.lon.toString(), (k * k).toString(),
            ),
        ).use { c -> buildList { while (c.moveToNext()) add(row(c)) } }.filter { Geo.distance(p, it.pos) <= radiusM }
    }

    private val COLS = "lang, pageid, title, lat, lon, description, length, intro, details, full IS NOT NULL"

    private fun row(c: Cursor) = Place(
        lang = c.getString(0), pageId = c.getLong(1), title = c.getString(2), lat = c.getDouble(3), lon = c.getDouble(4),
        description = if (c.isNull(5)) null else c.getString(5), length = c.getInt(6),
        intro = if (c.isNull(7)) null else c.getString(7), hasDetails = c.getInt(8) == 1, hasFull = c.getInt(9) == 1,
    )

    // ================================================================ online abgesuchte Gebiete

    fun recordArea(lang: String, center: LatLon, radius: Double) {
        writableDatabase.insert("area", null, ContentValues().apply {
            put("lang", lang); put("lat", center.lat); put("lon", center.lon); put("radius", radius); put("fetched", System.currentTimeMillis())
        })
    }

    /** Wurde um [p] schon gesucht, so dass [p] mindestens [margin] Meter innerhalb des Suchkreises liegt? */
    fun areaCovers(lang: String, p: LatLon, margin: Double, maxAgeMs: Long): Boolean {
        val since = System.currentTimeMillis() - maxAgeMs
        val box = Geo.bbox(p, 10_000.0)
        readableDatabase.rawQuery(
            "SELECT lat, lon, radius FROM area WHERE lang=? AND fetched>? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            arrayOf(lang, since.toString(), box.south.toString(), box.north.toString(), box.west.toString(), box.east.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val radius = c.getDouble(2)
                if (Geo.distance(p, LatLon(c.getDouble(0), c.getDouble(1))) + minOf(margin, radius * 0.4) <= radius) return true
            }
        }
        return false
    }

    // ================================================================ gehörte Orte

    fun markHeard(lang: String, pageId: Long) {
        writableDatabase.insertWithOnConflict("heard", null, ContentValues().apply {
            put("lang", lang); put("pageid", pageId); put("at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun heardSince(lang: String, since: Long): Set<Long> =
        readableDatabase.rawQuery("SELECT pageid FROM heard WHERE lang=? AND at>?", arrayOf(lang, since.toString())).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    fun clearHeard() { writableDatabase.delete("heard", null, null) }

    // ================================================================ Städte

    fun addCity(lang: String, name: String, box: BBox, withTexts: Boolean, withStreets: Boolean, withMap: Boolean): Long =
        writableDatabase.insert("city", null, ContentValues().apply {
            put("lang", lang); put("name", name); put("south", box.south); put("west", box.west); put("north", box.north); put("east", box.east)
            put("state", "queued"); put("with_texts", if (withTexts) 1 else 0); put("with_streets", if (withStreets) 1 else 0); put("with_map", if (withMap) 1 else 0)
            put("created", System.currentTimeMillis())
        })

    fun cities(): List<City> = readableDatabase.rawQuery(
        "SELECT id, lang, name, south, west, north, east, places, texts, streets, bytes, state, message, with_texts, with_streets, with_map FROM city ORDER BY name", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                City(
                    id = c.getLong(0), lang = c.getString(1), name = c.getString(2),
                    box = BBox(c.getDouble(3), c.getDouble(4), c.getDouble(5), c.getDouble(6)),
                    places = c.getInt(7), texts = c.getInt(8), streets = c.getInt(9), bytes = c.getLong(10),
                    state = c.getString(11) ?: "", message = c.getString(12) ?: "",
                    withTexts = c.getInt(13) == 1, withStreets = c.getInt(14) == 1, withMap = c.getInt(15) == 1,
                )
            )
        }
    }

    fun city(id: Long): City? = cities().firstOrNull { it.id == id }

    fun updateCity(id: Long, places: Int? = null, texts: Int? = null, streets: Int? = null, bytes: Long? = null, state: String? = null, message: String? = null) {
        val cv = ContentValues()
        places?.let { cv.put("places", it) }; texts?.let { cv.put("texts", it) }; streets?.let { cv.put("streets", it) }
        bytes?.let { cv.put("bytes", it) }; state?.let { cv.put("state", it) }; message?.let { cv.put("message", it) }
        if (cv.size() > 0) writableDatabase.update("city", cv, "id=?", arrayOf(id.toString()))
    }

    fun linkCity(cityId: Long, lang: String, ids: Collection<Long>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            ids.forEach { id ->
                db.insertWithOnConflict("city_place", null, ContentValues().apply { put("city", cityId); put("lang", lang); put("pageid", id) }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Seiten der Stadt, denen noch Kurzbeschreibung, Einleitung oder Volltext fehlt. */
    fun cityMissing(cityId: Long, column: String): List<Long> {
        val cond = when (column) {
            "details" -> "p.details=0"
            "intro" -> "p.intro IS NULL"
            else -> "p.full IS NULL"
        }
        return readableDatabase.rawQuery(
            "SELECT p.pageid FROM city_place cp JOIN place p ON p.lang=cp.lang AND p.pageid=cp.pageid WHERE cp.city=? AND $cond ORDER BY p.length DESC",
            arrayOf(cityId.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
    }

    fun cityBytes(cityId: Long): Long =
        readableDatabase.rawQuery(
            """SELECT COALESCE(SUM(LENGTH(p.full)),0) + COALESCE(SUM(LENGTH(p.intro)),0) FROM city_place cp
               JOIN place p ON p.lang=cp.lang AND p.pageid=cp.pageid WHERE cp.city=?""",
            arrayOf(cityId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0 } +
            readableDatabase.rawQuery("SELECT COALESCE(SUM(LENGTH(pts)),0) FROM street WHERE city=?", arrayOf(cityId.toString())).use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0
            }

    fun cityTextCount(cityId: Long): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM city_place cp JOIN place p ON p.lang=cp.lang AND p.pageid=cp.pageid WHERE cp.city=? AND (p.full IS NOT NULL OR p.intro IS NOT NULL)",
            arrayOf(cityId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** Stadt löschen; Orte nur, wenn keine andere Stadt sie braucht. */
    fun deleteCity(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                """DELETE FROM place WHERE EXISTS (SELECT 1 FROM city_place cp WHERE cp.city=? AND cp.lang=place.lang AND cp.pageid=place.pageid)
                   AND NOT EXISTS (SELECT 1 FROM city_place o WHERE o.city<>? AND o.lang=place.lang AND o.pageid=place.pageid)""",
                arrayOf(id, id),
            )
            db.delete("city_place", "city=?", arrayOf(id.toString()))
            db.delete("street", "city=?", arrayOf(id.toString()))
            db.delete("city", "id=?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Liegt [p] in einer fertig geladenen Stadt dieser Sprache? */
    fun coveredByCity(lang: String, p: LatLon): Boolean = cities().any { it.lang == lang && it.state == "done" && it.box.contains(p) }

    // ================================================================ Straßen

    fun deleteStreets(cityId: Long) { writableDatabase.delete("street", "city=?", arrayOf(cityId.toString())) }

    fun addStreets(cityId: Long, streets: List<Street>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (s in streets) {
                if (s.points.size < 2) continue
                db.insert("street", null, ContentValues().apply {
                    put("city", cityId); put("name", s.name)
                    put("s", s.points.minOf { it.lat }); put("n", s.points.maxOf { it.lat })
                    put("w", s.points.minOf { it.lon }); put("e", s.points.maxOf { it.lon })
                    put("pts", encodePoints(s.points))
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Name der nächsten Straße innerhalb von [maxM] Metern, oder null. */
    fun nearestStreet(p: LatLon, maxM: Double): String? {
        val box = Geo.bbox(p, maxM)
        var best: String? = null
        var bestD = maxM
        readableDatabase.rawQuery(
            "SELECT name, pts FROM street WHERE s<=? AND n>=? AND w<=? AND e>=?",
            arrayOf(box.north.toString(), box.south.toString(), box.east.toString(), box.west.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val pts = decodePoints(c.getBlob(1))
                for (i in 0 until pts.size - 1) {
                    val d = Geo.distanceToSegment(p, pts[i], pts[i + 1])
                    if (d < bestD) { bestD = d; best = c.getString(0) }
                }
            }
        }
        return best
    }

    fun hasStreetsNear(p: LatLon): Boolean {
        val box = Geo.bbox(p, 300.0)
        return readableDatabase.rawQuery(
            "SELECT 1 FROM street WHERE s<=? AND n>=? AND w<=? AND e>=? LIMIT 1",
            arrayOf(box.north.toString(), box.south.toString(), box.east.toString(), box.west.toString()),
        ).use { it.moveToFirst() }
    }

    companion object {
        fun gzip(s: String): ByteArray {
            val bos = ByteArrayOutputStream()
            GZIPOutputStream(bos).use { it.write(s.toByteArray(Charsets.UTF_8)) }
            return bos.toByteArray()
        }

        fun gunzip(b: ByteArray): String = GZIPInputStream(ByteArrayInputStream(b)).use { it.readBytes().toString(Charsets.UTF_8) }

        /** Punkte als Float-Paare (≈ 1 m genau, halbiert die Größe gegenüber Double). */
        fun encodePoints(pts: List<LatLon>): ByteArray {
            val bb = ByteBuffer.allocate(pts.size * 8).order(ByteOrder.LITTLE_ENDIAN)
            pts.forEach { bb.putFloat(it.lat.toFloat()); bb.putFloat(it.lon.toFloat()) }
            return bb.array()
        }

        fun decodePoints(b: ByteArray): List<LatLon> {
            val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            return List(b.size / 8) { LatLon(bb.getFloat().toDouble(), bb.getFloat().toDouble()) }
        }
    }
}
