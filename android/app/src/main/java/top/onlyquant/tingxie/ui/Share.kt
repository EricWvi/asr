package top.onlyquant.tingxie.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import top.onlyquant.tingxie.data.Doc
import top.onlyquant.tingxie.data.io
import java.io.File

/**
 * A document as Markdown to copy or share. Unless the text already opens
 * with a level-one heading, the title goes on top as one (as on the web).
 */
fun exportMarkdown(d: Doc): String = if (startsWithH1(d.content)) d.content else "# ${d.title}\n\n${d.content}"

private fun startsWithH1(content: String): Boolean {
    val lines = content.split("\n")
    val i = lines.indexOfFirst { it.isNotBlank() }
    if (i < 0) return false
    return Regex("^ {0,3}#(\\s|$)").containsMatchIn(lines[i]) ||
        Regex("^ {0,3}=+\\s*$").matches(lines.getOrNull(i + 1) ?: "")
}

/** Shares the document as a .md file through the system sheet. */
suspend fun shareMarkdown(context: Context, d: Doc) {
    val file = io {
        val dir = File(context.cacheDir, "share").apply { deleteRecursively(); mkdirs() }
        val name = d.title.replace(Regex("[/\\\\:*?\"<>|]"), "_").ifBlank { "文稿" }
        File(dir, "$name.md").apply { writeText(exportMarkdown(d)) }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/markdown")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, file.name)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}
