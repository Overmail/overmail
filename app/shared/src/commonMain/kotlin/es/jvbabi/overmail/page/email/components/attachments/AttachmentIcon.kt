package es.jvbabi.overmail.page.email.components.attachments

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.phosphor.icons.PhIcons
import com.phosphor.icons.regular.AddressBook
import com.phosphor.icons.regular.AndroidLogo
import com.phosphor.icons.regular.AppleLogo
import com.phosphor.icons.regular.Book
import com.phosphor.icons.regular.CalendarDots
import com.phosphor.icons.regular.Certificate
import com.phosphor.icons.regular.Cube
import com.phosphor.icons.regular.Database
import com.phosphor.icons.regular.Envelope
import com.phosphor.icons.regular.File
import com.phosphor.icons.regular.FileArchive
import com.phosphor.icons.regular.FileAudio
import com.phosphor.icons.regular.FileC
import com.phosphor.icons.regular.FileCSharp
import com.phosphor.icons.regular.FileCode
import com.phosphor.icons.regular.FileCpp
import com.phosphor.icons.regular.FileCss
import com.phosphor.icons.regular.FileCsv
import com.phosphor.icons.regular.FileDoc
import com.phosphor.icons.regular.FileHtml
import com.phosphor.icons.regular.FileImage
import com.phosphor.icons.regular.FileIni
import com.phosphor.icons.regular.FileJpg
import com.phosphor.icons.regular.FileJs
import com.phosphor.icons.regular.FileJsx
import com.phosphor.icons.regular.FileMd
import com.phosphor.icons.regular.FilePdf
import com.phosphor.icons.regular.FilePng
import com.phosphor.icons.regular.FilePpt
import com.phosphor.icons.regular.FilePy
import com.phosphor.icons.regular.FileRs
import com.phosphor.icons.regular.FileSql
import com.phosphor.icons.regular.FileSvg
import com.phosphor.icons.regular.FileTs
import com.phosphor.icons.regular.FileTsx
import com.phosphor.icons.regular.FileTxt
import com.phosphor.icons.regular.FileVideo
import com.phosphor.icons.regular.FileVue
import com.phosphor.icons.regular.FileXls
import com.phosphor.icons.regular.FileZip
import com.phosphor.icons.regular.Gif
import com.phosphor.icons.regular.Key
import com.phosphor.icons.regular.PenNib
import com.phosphor.icons.regular.TerminalWindow
import com.phosphor.icons.regular.TextAa
import com.phosphor.icons.regular.WindowsLogo
import es.jvbabi.overmail.ui.theme.AppTheme
import es.jvbabi.overmail.ui.theme.BaseColor
import es.jvbabi.overmail.ui.theme.BaseColors
import es.jvbabi.overmail.ui.theme.baseColors

/*
 * The web app's `AttachmentIcon`: which icon and which colour a file gets, by its extension, then
 * its content type, then the top-level part of that. Keep the two tables in step.
 */

private class FileKind(val icon: ImageVector, val color: BaseColors.() -> BaseColor)

private fun kind(icon: ImageVector, color: BaseColors.() -> BaseColor) = FileKind(icon, color)

private val image = kind(PhIcons.Regular.FileImage) { violet }
private val video = kind(PhIcons.Regular.FileVideo) { rose }
private val audio = kind(PhIcons.Regular.FileAudio) { fuchsia }
private val archive = kind(PhIcons.Regular.FileArchive) { amber }
private val code = kind(PhIcons.Regular.FileCode) { sky }
private val text = kind(PhIcons.Regular.FileTxt) { gray }
private val fallback = kind(PhIcons.Regular.File) { gray }

private val document = kind(PhIcons.Regular.FileDoc) { blue }
private val spreadsheet = kind(PhIcons.Regular.FileXls) { green }
private val table = kind(PhIcons.Regular.FileCsv) { emerald }
private val presentation = kind(PhIcons.Regular.FilePpt) { orange }
private val ebook = kind(PhIcons.Regular.Book) { lime }
private val markdown = kind(PhIcons.Regular.FileMd) { slate }
private val jpg = kind(PhIcons.Regular.FileJpg) { violet }
private val design = kind(PhIcons.Regular.PenNib) { pink }
private val js = kind(PhIcons.Regular.FileJs) { yellow }
private val c = kind(PhIcons.Regular.FileC) { blue }
private val cpp = kind(PhIcons.Regular.FileCpp) { blue }
private val html = kind(PhIcons.Regular.FileHtml) { orange }
private val config = kind(PhIcons.Regular.FileIni) { slate }
private val shell = kind(PhIcons.Regular.TerminalWindow) { slate }
private val calendar = kind(PhIcons.Regular.CalendarDots) { teal }
private val contact = kind(PhIcons.Regular.AddressBook) { cyan }
private val mail = kind(PhIcons.Regular.Envelope) { indigo }
private val certificate = kind(PhIcons.Regular.Certificate) { yellow }
private val key = kind(PhIcons.Regular.Key) { yellow }
private val font = kind(PhIcons.Regular.TextAa) { stone }
private val model = kind(PhIcons.Regular.Cube) { teal }
private val database = kind(PhIcons.Regular.Database) { cyan }
private val windows = kind(PhIcons.Regular.WindowsLogo) { slate }
private val apple = kind(PhIcons.Regular.AppleLogo) { slate }

private val byExtension: Map<String, FileKind> = buildMap {
    fun put(kind: FileKind, vararg extensions: String) = extensions.forEach { put(it, kind) }

    put(kind(PhIcons.Regular.FilePdf) { red }, "pdf")

    put(document, "doc", "docx", "odt", "rtf", "pages")
    put(spreadsheet, "xls", "xlsx", "ods", "numbers")
    put(table, "csv", "tsv")
    put(presentation, "ppt", "pptx", "odp", "key")
    put(ebook, "epub", "mobi")
    put(text, "txt", "log")
    put(markdown, "md", "markdown")

    put(kind(PhIcons.Regular.FilePng) { violet }, "png")
    put(jpg, "jpg", "jpeg")
    put(kind(PhIcons.Regular.Gif) { violet }, "gif")
    put(kind(PhIcons.Regular.FileSvg) { pink }, "svg")
    put(image, "webp", "heic", "heif", "avif", "bmp", "tif", "tiff", "ico")
    put(design, "psd", "ai", "sketch", "fig", "eps")

    put(video, "mp4", "mov", "avi", "mkv", "webm", "wmv", "m4v")
    put(audio, "mp3", "wav", "flac", "ogg", "m4a", "aac", "opus", "wma")

    put(kind(PhIcons.Regular.FileZip) { amber }, "zip")
    put(archive, "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst")

    put(js, "js", "mjs")
    put(kind(PhIcons.Regular.FileJsx) { sky }, "jsx")
    put(kind(PhIcons.Regular.FileTs) { blue }, "ts")
    put(kind(PhIcons.Regular.FileTsx) { blue }, "tsx")
    put(kind(PhIcons.Regular.FileVue) { emerald }, "vue")
    put(kind(PhIcons.Regular.FilePy) { sky }, "py")
    put(kind(PhIcons.Regular.FileRs) { orange }, "rs")
    put(c, "c", "h")
    put(cpp, "cpp", "cc", "hpp")
    put(kind(PhIcons.Regular.FileCSharp) { violet }, "cs")
    put(html, "html", "htm")
    put(kind(PhIcons.Regular.FileCss) { sky }, "css")
    put(kind(PhIcons.Regular.FileSql) { cyan }, "sql")
    put(config, "ini", "cfg", "conf", "toml", "env")
    put(code, "json", "xml", "yaml", "yml", "kt", "kts", "java", "go", "php", "rb", "swift", "dart", "svelte")
    put(shell, "sh", "bash", "zsh", "ps1", "bat")

    put(calendar, "ics", "ical")
    put(contact, "vcf", "vcard")
    put(mail, "eml", "msg")

    put(certificate, "pem", "crt", "cer", "p7s")
    put(key, "p12", "pfx", "asc", "gpg", "pgp", "sig")

    put(font, "ttf", "otf", "woff", "woff2")
    put(model, "stl", "obj", "glb", "gltf", "step")
    put(database, "db", "sqlite")

    put(windows, "exe", "msi")
    put(apple, "dmg", "pkg")
    put(kind(PhIcons.Regular.AndroidLogo) { green }, "apk")
}

/** For attachments without a known extension. */
private val byContentType: Map<String, FileKind> = mapOf(
    "application/pdf" to byExtension.getValue("pdf"),
    "text/calendar" to calendar,
    "text/vcard" to contact,
    "text/x-vcard" to contact,
    "message/rfc822" to mail,
    "text/html" to html,
    "text/csv" to table,
    "application/zip" to byExtension.getValue("zip"),
    "application/json" to code,
    "application/pkcs7-signature" to certificate,
    "application/pgp-signature" to key,
    "application/pgp-keys" to key,
)

private val byTopLevelType: Map<String, FileKind> = mapOf(
    "image" to image,
    "video" to video,
    "audio" to audio,
    "text" to text,
    "font" to font,
)

private fun fileKind(filename: String, contentType: String): FileKind {
    val extension = if ('.' in filename) filename.substringAfterLast('.').lowercase() else ""
    val type = contentType.lowercase()

    return byExtension[extension]
        ?: byContentType[type]
        ?: byTopLevelType[type.substringBefore('/')]
        ?: fallback
}

private val IconShape = RoundedCornerShape(4.dp)

/** A file's type as a tinted tile: the icon of what it is, on the colour of its kind. */
@Composable
fun AttachmentIcon(
    filename: String,
    contentType: String,
    modifier: Modifier = Modifier,
) {
    val kind = remember(filename, contentType) { fileKind(filename, contentType) }
    val color = kind.color(MaterialTheme.baseColors)

    Box(
        modifier = modifier
            .size(32.dp)
            .background(color.container, IconShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = kind.icon,
            contentDescription = null,
            tint = color.onContainer,
            modifier = Modifier.size(16.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachmentIconsContent() {
    Surface {
        FlowRow(
            modifier = Modifier.padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOf(
                "a.pdf", "a.docx", "a.xlsx", "a.csv", "a.pptx", "a.epub", "a.txt", "a.md", "a.png", "a.jpg",
                "a.gif", "a.svg", "a.webp", "a.psd", "a.mp4", "a.mp3", "a.zip", "a.tar", "a.js", "a.ts",
                "a.py", "a.rs", "a.kt", "a.html", "a.sql", "a.toml", "a.sh", "a.ics", "a.vcf", "a.eml",
                "a.pem", "a.asc", "a.ttf", "a.stl", "a.db", "a.exe", "a.dmg", "a.apk", "unknown",
            ).forEach { AttachmentIcon(filename = it, contentType = "application/octet-stream") }
        }
    }
}

@Preview
@Composable
private fun AttachmentIconsPreview() {
    AppTheme(dynamicColor = false) { AttachmentIconsContent() }
}

@Preview
@Composable
private fun AttachmentIconsDarkPreview() {
    AppTheme(darkTheme = true, dynamicColor = false) { AttachmentIconsContent() }
}
