package dev.wckdboy.autobot.feature.settings.about

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.wckdboy.autobot.core.designsystem.component.AutobotTopBar
import dev.wckdboy.autobot.core.designsystem.component.MicroLabel
import dev.wckdboy.autobot.core.designsystem.component.NavRow
import dev.wckdboy.autobot.core.designsystem.component.RowGutter
import dev.wckdboy.autobot.core.designsystem.component.RowMinHeight
import dev.wckdboy.autobot.core.designsystem.component.SectionLabel
import dev.wckdboy.autobot.core.designsystem.component.Tag
import dev.wckdboy.autobot.core.designsystem.theme.AutobotColors
import dev.wckdboy.autobot.core.designsystem.theme.AutobotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The bundled release notes for this version (`shared/release/whats-new.md`, packaged as an asset). */
const val WHATS_NEW_ASSET = "whats-new.md"

/** License texts of the bundled engines and fonts. */
const val LICENSES_ASSET_DIR = "licenses"

/**
 * About: the app, its version and build date, what's new in this release, links (opened in the
 * browser: nothing loads in the app), licenses, the privacy statement, the release signing key
 * (compared with this APK's own signer) and the diagnostics report.
 */
@Composable
fun AboutRoute(info: AboutInfo, onBack: () -> Unit, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    // Package manager and asset reads are I/O: off the main thread, once per screen.
    val content by produceState<AboutContent?>(null) {
        value = withContext(Dispatchers.IO) { loadAboutContent(context) }
    }
    AboutScreen(
        info = info,
        content = content ?: AboutContent(),
        onBack = onBack,
        onOpenLink = { url -> openInBrowser(context, url) },
        onOpenDiagnostics = onOpenDiagnostics,
    )
}

/** What About reads from the package: its icon, signer, release notes and license files. */
data class AboutContent(
    val icon: ImageBitmap? = null,
    /** This APK's signer, SHA-256 hex; null until read (or unreadable). */
    val signerSha256: String? = null,
    val notes: List<NoteBlock> = emptyList(),
    /** File names under [LICENSES_ASSET_DIR]. */
    val licenses: List<String> = emptyList(),
    /** False until the package has been read. */
    val loaded: Boolean = false,
)

private fun loadAboutContent(context: Context): AboutContent {
    val pm = context.packageManager
    val icon = runCatching { pm.getApplicationIcon(context.packageName).toBitmap(ICON_PX, ICON_PX).asImageBitmap() }.getOrNull()
    val signer = runCatching {
        val pkg = pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        // The signers of the APK's contents (the current key, not a rotated-from one).
        pkg.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()?.let(::sha256Hex)
    }.getOrNull()
    val notes = runCatching { context.assets.open(WHATS_NEW_ASSET).bufferedReader().use { it.readText() } }.getOrNull()?.let(::parseNotes).orEmpty()
    val licenses = runCatching { context.assets.list(LICENSES_ASSET_DIR)?.sorted() }.getOrNull().orEmpty()
    return AboutContent(icon, signer, notes, licenses, loaded = true)
}

/** Opens [url] in the browser (or whatever handles it); nothing loads inside the app. */
private fun openInBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun readLicense(context: Context, name: String): String =
    runCatching { context.assets.open("$LICENSES_ASSET_DIR/$name").bufferedReader().use { it.readText() } }.getOrDefault("")

@Composable
fun AboutScreen(
    info: AboutInfo,
    content: AboutContent,
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    var license by rememberSaveable { mutableStateOf<String?>(null) }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            AutobotTopBar(
                title = "About",
                navigation = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            // The app: icon, name, version (name + build number) and build date.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = RowGutter, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content.icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))) }
                Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Autobot", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("Version ${info.version}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (info.buildDate.isNotBlank()) {
                        Text("Built ${info.buildDate}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (info.debug) Tag("debug", accent = AutobotColors.Amber)
            }
            Text(
                AboutLinks.PRIVACY,
                Modifier.padding(horizontal = RowGutter).padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Divider()

            Header("What's new in ${info.versionName}")
            if (content.notes.isEmpty()) {
                MicroLabel("no release notes in this build", Modifier.padding(horizontal = RowGutter, vertical = 8.dp))
            } else {
                ReleaseNotes(content.notes, Modifier.padding(horizontal = RowGutter, vertical = 4.dp))
            }
            Divider()

            Header("Links")
            LinkRow("All releases", "github.com", onClick = { onOpenLink(AboutLinks.RELEASES) })
            LinkRow("Source code", "github.com", onClick = { onOpenLink(AboutLinks.SOURCE) })
            NavRow("Licenses", { showLicenses = true }, value = content.licenses.size.takeIf { it > 0 }?.toString())
            Divider()

            Header("Verify this APK")
            SignerBlock(info, content.signerSha256, content.loaded, onCopy = { clipboard.setText(AnnotatedString(AboutLinks.RELEASE_CERT_SHA256)) })
            Divider()

            Header("Support")
            NavRow("Copy diagnostics", onOpenDiagnostics, info = "A report of this phone's engines and errors, shown before it is copied. No prompts, paths or identifiers.")
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showLicenses) {
        AlertDialog(
            onDismissRequest = { showLicenses = false },
            title = { Text("Licenses") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    content.licenses.forEach { name ->
                        Text(
                            name.substringBeforeLast('.'),
                            Modifier.fillMaxWidth().clickable(role = Role.Button) { license = name }.padding(vertical = 12.dp),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showLicenses = false }) { Text("Close") } },
        )
    }
    license?.let { name ->
        val text by produceState("", name) { value = withContext(Dispatchers.IO) { readLicense(context, name) } }
        AlertDialog(
            onDismissRequest = { license = null },
            title = { Text(name.substringBeforeLast('.')) },
            text = {
                Text(
                    text,
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    style = AutobotTheme.styles.codeBlock,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmButton = { TextButton(onClick = { license = null }) { Text("Close") } },
        )
    }
}

/** The official key, a copy button, and whether this APK was signed with it. */
@Composable
private fun SignerBlock(info: AboutInfo, actual: String?, loaded: Boolean, onCopy: () -> Unit) {
    val match = certMatch(actual, debug = info.debug)
    Column(Modifier.padding(horizontal = RowGutter, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MicroLabel("release signer · sha-256", Modifier.weight(1f))
            TextButton(onClick = onCopy) { Text("COPY", style = MaterialTheme.typography.labelMedium) }
        }
        Text(groupedFingerprint(AboutLinks.RELEASE_CERT_SHA256), style = AutobotTheme.styles.codeBlock, color = MaterialTheme.colorScheme.onSurface)
        Text(
            if (loaded) match.headline else "Checking this app's signer…",
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                !loaded -> MaterialTheme.colorScheme.onSurfaceVariant
                match == CertMatch.OFFICIAL -> AutobotColors.Green
                match == CertMatch.DEBUG -> AutobotColors.Amber
                else -> MaterialTheme.colorScheme.error
            },
        )
        if (actual != null && match != CertMatch.OFFICIAL) {
            MicroLabel("this apk")
            Text(groupedFingerprint(actual), style = AutobotTheme.styles.codeBlock, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A link that leaves the app: `↗` instead of a chevron, and where it goes. */
@Composable
private fun LinkRow(title: String, host: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(role = Role.Button, onClickLabel = "Open $host in the browser", onClick = onClick)
            .padding(horizontal = RowGutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$host ↗", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The release notes: headings, bullets and paragraphs with **bold** and `code` runs. */
@Composable
private fun ReleaseNotes(blocks: List<NoteBlock>, modifier: Modifier = Modifier) {
    val code = SpanStyle(fontFamily = AutobotTheme.styles.codeBlock.fontFamily, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            val text = buildAnnotatedString {
                parseInline(block.text).forEach { span ->
                    when {
                        span.bold -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(span.text) }
                        span.code -> withStyle(code) { append(span.text) }
                        else -> append(span.text)
                    }
                }
            }
            when (block) {
                is NoteBlock.Heading -> Text(text, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                is NoteBlock.Bullet -> Row {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                is NoteBlock.Paragraph -> Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun Header(text: String) = SectionLabel(text, Modifier.padding(start = RowGutter, end = RowGutter, top = 12.dp, bottom = 4.dp))

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(vertical = 8.dp))

private const val ICON_PX = 192
