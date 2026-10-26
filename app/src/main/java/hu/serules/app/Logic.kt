package hu.serules.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class Rec(
    val ts: Long, val plate: String, val note: String,
    val photos: Int,                       // régebbi verzióban készült, nem megnyitható fotók száma
    val uris: List<String> = emptyList()   // megnyitható fotók
) {
    val count get() = photos + uris.size
}

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

object Store {
    private fun file(c: Context) = File(c.filesDir, "records.json")
    fun load(c: Context): List<Rec> {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            List(a.length()) {
                val o = a.getJSONObject(it)
                val u = o.optJSONArray("uris")
                Rec(o.getLong("ts"), o.getString("plate"), o.getString("note"), o.getInt("photos"),
                    if (u == null) emptyList() else List(u.length()) { i -> u.getString(i) })
            }
        } catch (e: Exception) { emptyList() }
    }
    fun save(c: Context, list: List<Rec>) {
        val a = JSONArray()
        list.forEach {
            a.put(JSONObject().put("ts", it.ts).put("plate", it.plate).put("note", it.note).put("photos", it.photos)
                .put("uris", JSONArray(it.uris)))
        }
        file(c).writeText(a.toString())
    }
}

/** Magyar rendszám: ABC-123 (régi) vagy ABCD-123 (új, AB [címer] CD-123). */
object Plate {
    private val toDigit = mapOf('O' to '0', 'Q' to '0', 'I' to '1', 'Z' to '2', 'S' to '5', 'G' to '6', 'B' to '8')
    private val toLetter = mapOf('0' to 'O', '1' to 'I', '2' to 'Z', '5' to 'S', '6' to 'G', '8' to 'B')

    fun parse(raw: String): String? {
        val s = raw.uppercase().filter { it.isLetterOrDigit() }
        if (s.length != 6 && s.length != 7) return null
        val nl = s.length - 3
        val sb = StringBuilder()
        for (i in s.indices) {
            val ch = s[i]
            if (i < nl) {
                sb.append(if (ch.isDigit()) (toLetter[ch] ?: return null) else ch)
            } else {
                sb.append(if (ch.isLetter()) (toDigit[ch] ?: return null) else ch)
            }
        }
        val r = sb.toString()
        if (!r.take(nl).all { it in 'A'..'Z' }) return null
        if (!r.drop(nl).all { it in '0'..'9' }) return null
        return r.take(nl) + "-" + r.drop(nl)
    }
}

object Xlsx {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun build(recs: List<Rec>): ByteArray {
        val sb = StringBuilder(
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><cols><col min="1" max="3" width="8" customWidth="1"/><col min="4" max="4" width="14" customWidth="1"/><col min="5" max="5" width="60" customWidth="1"/></cols><sheetData>"""
        )
        fun s(ref: String, t: String) = "<c r=\"$ref\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${esc(t)}</t></is></c>"
        fun n(ref: String, v: Int) = "<c r=\"$ref\"><v>$v</v></c>"
        sb.append("<row r=\"1\">").append(s("A1", "Év")).append(s("B1", "Hó")).append(s("C1", "Nap"))
            .append(s("D1", "Rendszám")).append(s("E1", "Megjegyzés")).append("</row>")
        recs.forEachIndexed { i, r ->
            val row = i + 2
            val c = Calendar.getInstance().apply { timeInMillis = r.ts }
            sb.append("<row r=\"$row\">")
                .append(n("A$row", c.get(Calendar.YEAR)))
                .append(n("B$row", c.get(Calendar.MONTH) + 1))
                .append(n("C$row", c.get(Calendar.DAY_OF_MONTH)))
                .append(s("D$row", r.plate)).append(s("E$row", r.note)).append("</row>")
        }
        sb.append("</sheetData></worksheet>")

        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            fun add(name: String, text: String) {
                z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry()
            }
            val head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
            add("[Content_Types].xml", head + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>")
            add("_rels/.rels", head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>")
            add("xl/workbook.xml", head + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"Sérülések\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>")
            add("xl/_rels/workbook.xml.rels", head + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/></Relationships>")
            add("xl/worksheets/sheet1.xml", sb.toString())
        }
        return bo.toByteArray()
    }

    /** Letöltések/serulesek mappába menti, visszaadja a fájl URI-ját. */
    fun export(ctx: Context, recs: List<Rec>): Uri? {
        val name = "serulesek_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".xlsx"
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, XLSX_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/serulesek")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return null
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(build(recs)) }
        return uri
    }
}
