package hu.serules.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) { App() } } }
    }
}

/** Élő kamerakép szövegfelismerése: 3 egyező találat után elfogadja a rendszámot. */
@OptIn(ExperimentalGetImage::class)
class PlateAnalyzer(private val onPlate: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val seen = mutableMapOf<String, Int>()
    private var done = false

    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || done) { proxy.close(); return }
        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        recognizer.process(img)
            .addOnSuccessListener { t ->
                val lines = t.textBlocks.flatMap { it.lines }.map { it.text }
                // A címer miatt a rendszám két sorra is szakadhat, ezért a szomszédos sorokat is összefűzzük.
                val candidates = lines + lines.zipWithNext { a, b -> a + b }
                val plate = candidates.firstNotNullOfOrNull { Plate.parse(it) }
                if (plate != null && !done) {
                    val n = (seen[plate] ?: 0) + 1
                    seen[plate] = n
                    if (n >= 3) { done = true; onPlate(plate) }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }
}

@Composable
fun CameraView(modifier: Modifier, analyzer: ImageAnalysis.Analyzer? = null, capture: ImageCapture? = null) {
    val owner = LocalContext.current as ComponentActivity
    AndroidView(modifier = modifier, factory = { c ->
        val pv = PreviewView(c)
        val future = ProcessCameraProvider.getInstance(c)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val uses = mutableListOf<UseCase>(preview)
            if (analyzer != null) {
                uses += ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    .also { it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer) }
            }
            if (capture != null) uses += capture
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, *uses.toTypedArray())
        }, ContextCompat.getMainExecutor(c))
        pv
    })
}

/** Kép mentése: Pictures/serulesek/<RENDSZÁM>/<ÉÉÉÉ-HH-NN>/ */
fun takePhoto(ctx: Context, cap: ImageCapture, plate: String, onDone: (String?) -> Unit) {
    val now = Date()
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
    val time = SimpleDateFormat("HH-mm-ss", Locale.US).format(now)
    val cv = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "${plate}_$time")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/serulesek/$plate/$day")
    }
    val opts = ImageCapture.OutputFileOptions.Builder(
        ctx.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv
    ).build()
    cap.takePicture(opts, ContextCompat.getMainExecutor(ctx), object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(r: ImageCapture.OutputFileResults) = onDone(r.savedUri?.toString())
        override fun onError(e: ImageCaptureException) = onDone(null)
    })
}

fun deletePhoto(ctx: Context, uri: String) {
    try { ctx.contentResolver.delete(Uri.parse(uri), null, null) }
    catch (e: Exception) {
        Toast.makeText(ctx, "A fájlt nem sikerült törölni a telefonról, a listából eltávolítottam", Toast.LENGTH_LONG).show()
    }
}

@Composable
fun PhotoScreen(
    plate: String, hasCam: Boolean, count: Int, nextText: String,
    onPhoto: (String) -> Unit, onNext: () -> Unit, onEditPlate: (() -> Unit)?
) {
    val ctx = LocalContext.current
    val capture = remember { ImageCapture.Builder().build() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(plate, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            if (onEditPlate != null) TextButton(onEditPlate) { Text("Rendszám módosítása") }
        }
        if (hasCam) CameraView(Modifier.weight(1f).fillMaxWidth(), capture = capture)
        else Text("Kamera-engedély szükséges.", Modifier.weight(1f).padding(20.dp))
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                takePhoto(ctx, capture, plate) { u ->
                    if (u != null) onPhoto(u) else Toast.makeText(ctx, "A mentés nem sikerült", Toast.LENGTH_SHORT).show()
                }
            }) { Text("📷 Fotó ($count)") }
            OutlinedButton(onNext) { Text(nextText) }
        }
    }
}

fun dayOf(ts: Long): String = SimpleDateFormat("yyyy.MM.dd.", Locale.US).format(Date(ts))

@Composable
fun GroupRow(title: String, sub: String, onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.fillMaxWidth().height(68.dp)) {
        Text("$title  ·  $sub", fontSize = 18.sp)
    }
}

@Composable
fun BigButton(text: String, primary: Boolean = true, onClick: () -> Unit) {
    val m = Modifier.fillMaxWidth().widthIn(max = 520.dp).height(88.dp)
    if (primary) Button(onClick, m) { Text(text, fontSize = 20.sp, textAlign = TextAlign.Center) }
    else OutlinedButton(onClick, m) { Text(text, fontSize = 20.sp, textAlign = TextAlign.Center) }
}

fun exportAndShare(ctx: Context, recs: List<Rec>) {
    val uri = Xlsx.export(ctx, recs)
    if (uri == null) {
        Toast.makeText(ctx, "Az export nem sikerült", Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(ctx, "Mentve: Letöltések/serulesek", Toast.LENGTH_LONG).show()
    val send = Intent(Intent.ACTION_SEND).setType(XLSX_MIME)
        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Megosztás"))
}

@Composable
fun App() {
    val ctx = LocalContext.current
    var screen by rememberSaveable { mutableStateOf("home") }
    var plate by rememberSaveable { mutableStateOf("") }
    var photoUris by rememberSaveable { mutableStateOf("") }
    var selTs by rememberSaveable { mutableLongStateOf(0L) }
    var viewIdx by rememberSaveable { mutableIntStateOf(0) }
    var listPlate by rememberSaveable { mutableStateOf("") }
    var listDay by rememberSaveable { mutableStateOf("") }
    val listUp = {
        if (listDay.isNotEmpty()) listDay = ""
        else if (listPlate.isNotEmpty()) listPlate = ""
        else screen = "home"
    }
    val newUris = photoUris.split("\n").filter { it.isNotEmpty() }
    var note by rememberSaveable { mutableStateOf("") }
    var recs by remember { mutableStateOf(Store.load(ctx)) }
    var hasCam by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCam = it }
    LaunchedEffect(Unit) { if (!hasCam) permLauncher.launch(Manifest.permission.CAMERA) }

    BackHandler(screen != "home") {
        if (screen == "list") listUp() else screen = when (screen) { "photos" -> "edit"; "note" -> "photos"; "viewer" -> "detail"; "addphoto" -> "detail"; "detail" -> "list"; else -> "home" }
    }

    when (screen) {
        "home" -> BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🚗 Sérülésnapló", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                BigButton("Új sérülés\n(rendszám beolvasása)") { plate = ""; photoUris = ""; note = ""; screen = "scan" }
                recs.lastOrNull()?.let { last ->
                    BigButton("Új sérülés az előző rendszámmal\n${last.plate}", primary = false) {
                        plate = last.plate; photoUris = ""; note = ""; screen = "photos"
                    }
                }
                if (recs.isNotEmpty()) {
                    BigButton("📊 Export Excelbe (.xlsx)", primary = false) { exportAndShare(ctx, recs) }
                }
                BigButton("📋 Lista (${recs.size})", primary = false) { listPlate = ""; listDay = ""; screen = "list" }
            }
        }

        "scan" -> {
            val analyzer = remember { PlateAnalyzer { p -> plate = p; photoUris = ""; screen = "photos" } }
            Column(Modifier.fillMaxSize()) {
                if (hasCam) CameraView(Modifier.weight(1f).fillMaxWidth(), analyzer = analyzer)
                else Text("Kamera-engedély szükséges.", Modifier.weight(1f).padding(20.dp))
                Text("Tartsd a kamerát a rendszám felé, magától felismeri.", Modifier.padding(12.dp))
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ screen = "home" }) { Text("Vissza") }
                    Button({ screen = "edit" }) { Text("Kézi megadás") }
                }
            }
        }

        "edit" -> {
            var text by rememberSaveable { mutableStateOf(plate) }
            val parsed = Plate.parse(text)
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Rendszám módosítása", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(text, { text = it.uppercase() }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("ABC-123 vagy ABCD-123") },
                    supportingText = { Text(if (parsed != null) "✓ $parsed" else "Érvénytelen formátum") })
                Button({ plate = parsed!!; screen = "photos" }, Modifier.fillMaxWidth(), enabled = parsed != null) { Text("Kész, tovább a fotókhoz") }
                OutlinedButton({ screen = "scan" }, Modifier.fillMaxWidth()) { Text("Újraolvasás kamerával") }
            }
        }

        "photos" -> PhotoScreen(plate, hasCam, newUris.size, "Tovább a megjegyzéshez",
            onPhoto = { u -> photoUris = if (photoUris.isEmpty()) u else photoUris + "\n" + u },
            onNext = { screen = "note" }, onEditPlate = { screen = "edit" })

        "addphoto" -> {
            val rec = recs.find { it.ts == selTs }
            PhotoScreen(rec?.plate ?: "", hasCam, rec?.count ?: 0, "Kész",
                onPhoto = { u ->
                    recs = recs.map { if (it.ts == selTs) it.copy(uris = it.uris + u) else it }
                    Store.save(ctx, recs)
                },
                onNext = { screen = "detail" }, onEditPlate = null)
        }

        "note" -> {
            val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                val t = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (t != null) note = if (note.isBlank()) t else "$note $t"
            }
            Column(Modifier.fillMaxSize().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$plate · ${newUris.size} fotó", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), minLines = 4,
                    label = { Text("Megjegyzés (sérülés mértéke, helye stb.)") })
                OutlinedButton({
                    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "hu-HU")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Diktáld a megjegyzést")
                    try { speech.launch(i) } catch (e: ActivityNotFoundException) {
                        Toast.makeText(ctx, "Nincs elérhető hangfelismerés", Toast.LENGTH_SHORT).show()
                    }
                }, Modifier.fillMaxWidth()) { Text("🎤 Diktálás") }
                Button({
                    recs = recs + Rec(System.currentTimeMillis(), plate, note.trim(), 0, newUris)
                    Store.save(ctx, recs)
                    Toast.makeText(ctx, "Mentve ✓", Toast.LENGTH_SHORT).show()
                    screen = "home"
                }, Modifier.fillMaxWidth()) { Text("💾 Mentés") }
            }
        }

        "detail" -> {
            val rec = recs.find { it.ts == selTs }
            if (rec == null) LaunchedEffect(Unit) { screen = "list" }
            else Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(rec.plate, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(SimpleDateFormat("yyyy.MM.dd. HH:mm", Locale.US).format(Date(rec.ts)))
                Text(rec.note.ifBlank { "(nincs megjegyzés)" })
                if (rec.photos > 0) Text("+ ${rec.photos} korábbi fotó, ami ebben a verzióban nem jeleníthető meg.")
                LazyVerticalGrid(GridCells.Adaptive(110.dp), Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(rec.uris) { i, u ->
                        AsyncImage(model = u, contentDescription = "Sérülésfotó", contentScale = ContentScale.Crop,
                            modifier = Modifier.aspectRatio(1f).clickable { viewIdx = i; screen = "viewer" })
                    }
                }
                Button({ screen = "addphoto" }, Modifier.fillMaxWidth()) { Text("📷 További fotó készítése") }
                OutlinedButton({ screen = "list" }, Modifier.fillMaxWidth()) { Text("Vissza") }
            }
        }

        "viewer" -> {
            val rec = recs.find { it.ts == selTs }
            var confirm by remember { mutableStateOf(false) }
            if (rec == null || rec.uris.isEmpty()) LaunchedEffect(Unit) { screen = "detail" }
            else {
                val idx = viewIdx.coerceIn(0, rec.uris.size - 1)
                Column(Modifier.fillMaxSize()) {
                    AsyncImage(model = rec.uris[idx], contentDescription = "Sérülésfotó", contentScale = ContentScale.Fit,
                        modifier = Modifier.weight(1f).fillMaxWidth())
                    Text("${idx + 1} / ${rec.uris.size}", Modifier.align(Alignment.CenterHorizontally))
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({ viewIdx = idx - 1 }, enabled = idx > 0) { Text("‹") }
                        OutlinedButton({ viewIdx = idx + 1 }, enabled = idx < rec.uris.size - 1) { Text("›") }
                        Button({ confirm = true }) { Text("🗑 Törlés") }
                        OutlinedButton({ screen = "detail" }) { Text("Vissza") }
                    }
                }
                if (confirm) AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Fotó törlése") },
                    text = { Text("Biztosan törlöd ezt a fotót a telefonról is?") },
                    confirmButton = {
                        TextButton({
                            confirm = false
                            deletePhoto(ctx, rec.uris[idx])
                            recs = recs.map { if (it.ts == rec.ts) it.copy(uris = it.uris.filterIndexed { i, _ -> i != idx }) else it }
                            Store.save(ctx, recs)
                            viewIdx = (idx - 1).coerceAtLeast(0)
                        }) { Text("Törlés") }
                    },
                    dismissButton = { TextButton({ confirm = false }) { Text("Mégse") } }
                )
            }
        }

        "list" -> Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = when {
                listDay.isNotEmpty() -> "$listPlate · $listDay"
                listPlate.isNotEmpty() -> listPlate
                else -> "Rögzített sérülések"
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            if (recs.isEmpty()) Text("Még nincs rögzített sérülés.")
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (listPlate.isEmpty()) {
                    val plates = recs.groupBy { it.plate }.entries.sortedBy { it.key }
                    items(plates) { g ->
                        GroupRow(g.key, "${g.value.size} sérülés · ${g.value.map { dayOf(it.ts) }.distinct().size} nap") { listPlate = g.key }
                    }
                } else if (listDay.isEmpty()) {
                    val days = recs.filter { it.plate == listPlate }.groupBy { dayOf(it.ts) }.entries.sortedByDescending { it.key }
                    items(days) { d -> GroupRow(d.key, "${d.value.size} sérülés") { listDay = d.key } }
                } else {
                    val day = recs.filter { it.plate == listPlate && dayOf(it.ts) == listDay }.sortedByDescending { it.ts }
                    if (day.isEmpty()) item { Text("Nincs több sérülés ezen a napon.") }
                    items(day) { r ->
                        Column(Modifier.fillMaxWidth().clickable { selTs = r.ts; screen = "detail" }) {
                            Text("${SimpleDateFormat("HH:mm", Locale.US).format(Date(r.ts))} · ${r.count} fotó", fontWeight = FontWeight.Bold)
                            Text(r.note.ifBlank { "(nincs megjegyzés)" })
                            TextButton({ recs = recs.filter { it !== r }; Store.save(ctx, recs) }) { Text("Törlés") }
                        }
                    }
                }
            }
            Button({ exportAndShare(ctx, recs) }, Modifier.fillMaxWidth(), enabled = recs.isNotEmpty()) { Text("📊 Export Excelbe (.xlsx)") }
            OutlinedButton({ listUp() }, Modifier.fillMaxWidth()) { Text("Vissza") }
        }
    }
}
