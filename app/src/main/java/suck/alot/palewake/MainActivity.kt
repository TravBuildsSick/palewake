package suck.alot.palewake

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import coil.compose.SubcomposeAsyncImageContent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val GITHUB_OWNER = "TravBuildsSick"
private const val GITHUB_REPO = "palewake"

// Raw file, not the API — no rate limit, ~5 min CDN cache. Lists which repos are in the store.
private const val CATALOG_URL =
    "https://raw.githubusercontent.com/$GITHUB_OWNER/$GITHUB_REPO/main/catalog.json"

private val NavyDeep = Color(0xFF0A0E1C)
private val NavyDeeper = Color(0xFF050710)
private val NavySurface = Color(0xFF141A33)
private val NavySurfaceLight = Color(0xFF232B4D)
private val RedAccent = Color(0xFFFF3B57)
private val RedAccentDark = Color(0xFFB0203A)
private val IceBlue = Color(0xFF5EC8FF)
private val TextPrimary = Color(0xFFF5F7FF)
private val TextSecondary = Color(0xFF8D96BF)

// One entry per app in the store; lives in catalog.json at the repo root.
data class CatalogEntry(
    val id: String,
    val name: String,
    val description: String,
    val packageName: String,
    val repo: String, // "owner/repo" — its own release repo, not necessarily this one
    val iconUrl: String?,
)

// A catalog entry merged with its latest GitHub release.
data class StoreApp(
    val id: String,
    val name: String,
    val description: String,
    val packageName: String,
    val repo: String,
    val iconUrl: String?,
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long,
)

private fun fetchCatalog(): List<CatalogEntry> {
    val connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
    connection.connectTimeout = 8000
    connection.readTimeout = 8000
    try {
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val array = org.json.JSONArray(body)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CatalogEntry(
                id = o.getString("id"),
                name = o.getString("name"),
                description = o.optString("description", ""),
                packageName = o.optString("package_name", ""),
                repo = o.getString("repo"),
                iconUrl = o.optString("icon_url", "").takeIf { it.isNotBlank() },
            )
        }
    } finally {
        connection.disconnect()
    }
}

// Returns null (rather than throwing) when a repo has no release yet or no .apk asset —
// one broken entry shouldn't take down the whole catalog screen.
private fun fetchLatestRelease(entry: CatalogEntry): StoreApp? {
    val connection =
        URL("https://api.github.com/repos/${entry.repo}/releases/latest").openConnection() as HttpURLConnection
    connection.connectTimeout = 8000
    connection.readTimeout = 8000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    try {
        if (connection.responseCode !in 200..299) return null
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val o = org.json.JSONObject(body)
        val tagName = o.getString("tag_name") // e.g. "v4" — the versionCode
        val versionCode = tagName.trimStart('v').toIntOrNull() ?: return null
        val assets = o.getJSONArray("assets")
        val apkAsset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        return StoreApp(
            id = entry.id,
            name = entry.name,
            description = entry.description,
            packageName = entry.packageName,
            repo = entry.repo,
            iconUrl = entry.iconUrl,
            versionCode = versionCode,
            versionName = o.optString("name", tagName),
            notes = o.optString("body", ""),
            downloadUrl = apkAsset.getString("browser_download_url"),
            sizeBytes = apkAsset.optLong("size", 0L),
        )
    } catch (e: Exception) {
        return null
    } finally {
        connection.disconnect()
    }
}

// Full release history for one app (not just latest) — used by the version-picker sheet.
// Tags that aren't a plain "v<versionCode>" (e.g. wrackline's "pack-*" content releases) are
// skipped, same as a release with no usable .apk asset.
private fun fetchReleaseHistory(app: StoreApp): List<StoreApp> {
    val connection =
        URL("https://api.github.com/repos/${app.repo}/releases").openConnection() as HttpURLConnection
    connection.connectTimeout = 8000
    connection.readTimeout = 8000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    try {
        if (connection.responseCode !in 200..299) return emptyList()
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val array = org.json.JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val tagName = o.getString("tag_name")
            if (!Regex("^v\\d+$").matches(tagName)) return@mapNotNull null
            val versionCode = tagName.trimStart('v').toIntOrNull() ?: return@mapNotNull null
            val assets = o.getJSONArray("assets")
            val apkAsset = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") } ?: return@mapNotNull null
            StoreApp(
                id = app.id,
                name = app.name,
                description = app.description,
                packageName = app.packageName,
                repo = app.repo,
                iconUrl = app.iconUrl,
                versionCode = versionCode,
                versionName = o.optString("name", tagName),
                notes = o.optString("body", ""),
                downloadUrl = apkAsset.getString("browser_download_url"),
                sizeBytes = apkAsset.optLong("size", 0L),
            )
        }.sortedByDescending { it.versionCode }
    } catch (e: Exception) {
        return emptyList()
    } finally {
        connection.disconnect()
    }
}

private suspend fun fetchApps(): List<StoreApp> = coroutineScope {
    val catalog = fetchCatalog()
    catalog
        .map { entry -> async { fetchLatestRelease(entry) } }
        .awaitAll()
        .filterNotNull()
}

private fun downloadApk(url: String, destination: File): File {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 8000
    connection.readTimeout = 30000
    connection.instanceFollowRedirects = true
    try {
        connection.inputStream.use { input ->
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destination
    } finally {
        connection.disconnect()
    }
}

private fun installedVersionCode(context: android.content.Context, packageName: String): Int? {
    return try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
}

@Composable
private fun rememberPulse(min: Float = 0.35f, max: Float = 1f, durationMs: Int = 1600): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(
        initialValue = min,
        targetValue = max,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    return pulse
}

@Composable
private fun GlowSpinner(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 28.dp) {
    val glow = rememberPulse(min = 0.4f, max = 0.9f, durationMs = 1100)
    Box(
        modifier = modifier
            .size(size)
            .shadow(
                elevation = (glow * 10).dp,
                shape = CircleShape,
                ambientColor = RedAccent,
                spotColor = RedAccent,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = RedAccent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(size),
        )
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / 1024.0 / 1024.0
    return "%.1f MB".format(mb)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val colorScheme = darkColorScheme(
                primary = RedAccent,
                onPrimary = Color.White,
                secondary = IceBlue,
                background = NavyDeep,
                onBackground = TextPrimary,
                surface = NavySurface,
                onSurface = TextPrimary,
                surfaceVariant = NavySurfaceLight,
                onSurfaceVariant = TextSecondary,
                error = RedAccent,
            )
            MaterialTheme(colorScheme = colorScheme) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(NavyDeep, NavyDeeper))),
                ) {
                    StoreScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<StoreApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshToken by remember { mutableStateOf(0) }

    var historyApp by remember { mutableStateOf<StoreApp?>(null) }
    var historyVersions by remember { mutableStateOf<List<StoreApp>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }

    LaunchedEffect(historyApp) {
        val app = historyApp ?: return@LaunchedEffect
        historyLoading = true
        historyVersions = withContext(Dispatchers.IO) { fetchReleaseHistory(app) }
        historyLoading = false
    }

    LaunchedEffect(refreshToken) {
        loading = true
        error = null
        try {
            apps = withContext(Dispatchers.IO) { fetchApps() }
        } catch (e: Exception) {
            error = "Couldn't reach the catalog: ${e.message}"
        } finally {
            loading = false
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text("PALEWAKE", fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = TextPrimary,
                    ),
                    actions = {
                        IconButton(onClick = { refreshToken++ }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = RedAccent)
                        }
                    },
                )
                val dividerGlow = rememberPulse(min = 0.5f, max = 1f, durationMs = 2200)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .shadow(
                            elevation = (dividerGlow * 8).dp,
                            ambientColor = RedAccent,
                            spotColor = RedAccent,
                        )
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    RedAccent.copy(alpha = dividerGlow),
                                    IceBlue.copy(alpha = 0.4f * dividerGlow),
                                    Color.Transparent,
                                ),
                            ),
                        ),
                )
            }
        },
    ) { padding ->
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = loading && apps.isNotEmpty(),
            onRefresh = { refreshToken++ },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                loading && apps.isEmpty() -> GlowSpinner(modifier = Modifier.align(Alignment.Center))
                error != null -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(error!!, color = RedAccent)
                    Button(
                        onClick = { refreshToken++ },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = RedAccent,
                            contentColor = Color.White,
                        ),
                    ) { Text("Retry") }
                }
                apps.isEmpty() -> Text(
                    "No apps published yet",
                    color = TextSecondary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(apps, key = { it.id }) { app ->
                        AppCard(
                            app = app,
                            scope = scope,
                            context = context,
                            onOpenHistory = { historyApp = app },
                        )
                    }
                }
            }
        }
    }

    val sheetApp = historyApp
    if (sheetApp != null) {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { historyApp = null },
            containerColor = NavySurface,
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    "${sheetApp.name} — versions",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                when {
                    historyLoading -> GlowSpinner(modifier = Modifier.padding(bottom = 24.dp))
                    historyVersions.isEmpty() -> Text(
                        "No release history found.",
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                    else -> Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 24.dp),
                    ) {
                        historyVersions.forEach { version ->
                            VersionRow(app = version, scope = scope, context = context)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppCard(
    app: StoreApp,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    onOpenHistory: () -> Unit,
) {
    var status by remember(app.id) { mutableStateOf<String?>(null) }
    var busy by remember(app.id) { mutableStateOf(false) }
    val installedCode = remember(app.id) {
        if (app.packageName.isNotBlank()) installedVersionCode(context, app.packageName) else null
    }
    val isInstalled = installedCode != null
    val hasUpdate = isInstalled && installedCode!! < app.versionCode
    val cardShape = RoundedCornerShape(20.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(
                Brush.linearGradient(
                    listOf(NavySurfaceLight.copy(alpha = 0.55f), NavySurface.copy(alpha = 0.35f)),
                ),
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(RedAccent.copy(alpha = 0.45f), NavySurfaceLight.copy(alpha = 0.15f), IceBlue.copy(alpha = 0.25f)),
                ),
                shape = cardShape,
            )
            .clickable { onOpenHistory() }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val iconGlow = rememberPulse(min = 0.25f, max = 0.55f, durationMs = 2400)
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(listOf(RedAccent.copy(alpha = iconGlow), Color.Transparent)),
                        ),
                )
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(RedAccent, RedAccentDark))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (app.id == "palewake") {
                        Image(
                            painter = painterResource(R.drawable.ic_palewake),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                        )
                    } else if (app.iconUrl != null) {
                        coil.compose.SubcomposeAsyncImage(
                            model = app.iconUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape),
                        ) {
                            val painterState = painter.state
                            if (painterState is coil.compose.AsyncImagePainter.State.Success) {
                                SubcomposeAsyncImageContent()
                            } else {
                                Text(
                                    app.name.take(1).uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                )
                            }
                        }
                    } else {
                        Text(
                            app.name.take(1).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(app.name, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                if (app.description.isNotBlank()) {
                    Text(app.description, fontSize = 13.sp, color = TextSecondary)
                }
                Text(
                    "v${app.versionName} · ${formatSize(app.sizeBytes)}",
                    fontSize = 12.sp,
                    color = TextSecondary,
                )
                if (isInstalled) {
                    Text(
                        if (hasUpdate) "Update available" else "Installed — up to date",
                        fontSize = 12.sp,
                        color = if (hasUpdate) RedAccent else IceBlue,
                        fontWeight = FontWeight.Medium,
                    )
                }
                status?.let {
                    Text(it, fontSize = 12.sp, color = TextSecondary)
                }
            }

            if (busy) {
                GlowSpinner(size = 24.dp)
            } else if (hasUpdate || !isInstalled) {
                val label = if (!isInstalled) "Install" else "Update"
                val onClick = {
                    scope.launch {
                        busy = true
                        status = "Downloading..."
                        try {
                            val dest = File(context.cacheDir, "${app.id}.apk")
                            val file = withContext(Dispatchers.IO) { downloadApk(app.downloadUrl, dest) }
                            status = "Installing..."
                            installApk(context, file)
                            status = null
                        } catch (e: Exception) {
                            status = "Failed: ${e.message}"
                        } finally {
                            busy = false
                        }
                    }
                    Unit
                }
                val buttonGlow = rememberPulse(min = 6f, max = 16f, durationMs = 1400)
                Box(
                    modifier = Modifier
                        .shadow(buttonGlow.dp, RoundedCornerShape(12.dp), ambientColor = RedAccent, spotColor = RedAccent)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Brush.linearGradient(listOf(RedAccent, RedAccentDark)))
                        .clickable { onClick() }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            } else {
                Text("Installed", color = IceBlue, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }
    }
}

// One row in the version-history bottom sheet — same install/update logic as AppCard's button,
// scoped to one specific past release rather than always the latest.
@Composable
private fun VersionRow(
    app: StoreApp,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    var status by remember(app.versionCode) { mutableStateOf<String?>(null) }
    var busy by remember(app.versionCode) { mutableStateOf(false) }
    val installedCode = remember(app.versionCode) {
        if (app.packageName.isNotBlank()) installedVersionCode(context, app.packageName) else null
    }
    val isThisInstalled = installedCode == app.versionCode
    val isInstalled = installedCode != null
    val hasUpdate = isInstalled && installedCode!! < app.versionCode

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(NavySurfaceLight.copy(alpha = 0.4f))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "v${app.versionName} · ${formatSize(app.sizeBytes)}",
                color = TextPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )
            if (app.notes.isNotBlank()) {
                Text(app.notes, color = TextSecondary, fontSize = 12.sp, maxLines = 2)
            }
            status?.let { Text(it, color = TextSecondary, fontSize = 12.sp) }
        }
        Spacer(modifier = Modifier.size(8.dp))
        if (busy) {
            GlowSpinner(size = 20.dp)
        } else if (isThisInstalled) {
            Text("Installed", color = IceBlue, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        } else {
            val label = if (!isInstalled) "Install" else if (hasUpdate) "Update" else "Downgrade"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(RedAccent)
                    .clickable {
                        scope.launch {
                            busy = true
                            status = "Downloading..."
                            try {
                                val dest = File(context.cacheDir, "${app.id}-${app.versionCode}.apk")
                                val file = withContext(Dispatchers.IO) { downloadApk(app.downloadUrl, dest) }
                                status = "Installing..."
                                installApk(context, file)
                                status = null
                            } catch (e: Exception) {
                                status = "Failed: ${e.message}"
                            } finally {
                                busy = false
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}

private fun installApk(context: android.content.Context, apkFile: File) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        val settingsIntent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        context.startActivity(settingsIntent)
        return
    }

    val uri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile,
    )
    val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(installIntent)
}
