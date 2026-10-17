package eu.kanade.tachiyomi.data.export

import android.content.Context
import android.graphics.BitmapFactory
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.storage.DiskUtil
import exh.log.xLogE
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.OutputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds one EPUB per series from its downloaded chapters, for readers like Readest.
 *
 * Every page is a full-width block with the image as its background: readers built on
 * foliate-js (Readest) force each <img> to fit the screen height, which squeezes tall
 * manhwa strips into a narrow column. Backgrounds are left alone, so pages stay full
 * width and join without gaps in scrolled mode.
 */
class EpubExporter(
    private val context: Context,
    private val provider: DownloadProvider = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
) {

    /**
     * Rebuilds the EPUB of [manga] from all of its downloaded chapters.
     *
     * @return the written file, or null when the series has no downloaded chapters.
     */
    suspend fun export(manga: Manga): UniFile? = mutex.withLock {
        withIOContext {
            val exportDir = storageManager.getEpubExportDirectory() ?: return@withIOContext null
            val source = sourceManager.getOrStub(manga.source)
            val chapters = findDownloadedChapters(manga, source)
            if (chapters.isEmpty()) return@withIOContext null

            val fileName = DiskUtil.buildValidFilename(manga.title) + ".epub"
            val tmpName = "$fileName.tmp"
            exportDir.findFile(tmpName)?.delete()
            val tmpFile = exportDir.createFile(tmpName) ?: error("Failed to create $tmpName")
            try {
                tmpFile.openOutputStream().use { writeEpub(it, manga, chapters) }
            } catch (e: Throwable) {
                tmpFile.delete()
                throw e
            }
            exportDir.findFile(fileName)?.delete()
            tmpFile.renameTo(fileName)
            exportDir.findFile(fileName)
        }
    }

    /** Downloaded chapters of [manga] in reading order, with their folder or CBZ file. */
    private suspend fun findDownloadedChapters(manga: Manga, source: Source): List<Pair<Chapter, UniFile>> {
        val mangaDir = provider.findMangaDir(manga.ogTitle, source) ?: return emptyList()
        val files = mangaDir.listFiles().orEmpty().associateBy { it.name.orEmpty() }
        return getChaptersByMangaId.await(manga.id)
            // Sources list the newest chapter first
            .sortedByDescending { it.sourceOrder }
            .mapNotNull { chapter ->
                provider.getValidChapterDirNames(chapter.name, chapter.scanlator, chapter.url)
                    .firstNotNullOfOrNull { files[it] }
                    ?.let { chapter to it }
            }
    }

    private fun writeEpub(output: OutputStream, manga: Manga, chapters: List<Pair<Chapter, UniFile>>) {
        val title = manga.title
        val toc = mutableListOf<Pair<String, String>>()
        val imageItems = StringBuilder()
        var coverHref: String? = null

        ZipOutputStream(output.buffered()).use { zip ->
            // The mimetype entry must come first and be stored uncompressed
            zip.putStored("mimetype", "application/epub+zip".toByteArray())
            zip.putText(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="utf-8"?>
                |<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                |<rootfiles>
                |<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                |</rootfiles>
                |</container>
                """.trimMargin(),
            )
            zip.putText("OEBPS/style.css", STYLE_CSS)

            chapters.forEachIndexed { index, (chapter, file) ->
                val ci = index + 1
                val href = "c%04d.xhtml".format(ci)
                val rules = StringBuilder()
                val body = StringBuilder()
                var pageNumber = 0
                try {
                    forEachImage(file) { bytes ->
                        pageNumber++
                        val type = ImageUtil.findImageType(bytes.inputStream())
                        val ext = type?.extension ?: "jpg"
                        val mime = type?.mime ?: "image/jpeg"
                        val imgHref = "img/%04d_%04d.%s".format(ci, pageNumber, ext)
                        zip.putStored("OEBPS/$imgHref", bytes)

                        val isCover = coverHref == null
                        if (isCover) coverHref = imgHref
                        val id = if (isCover) "cover-img" else "i%04d_%04d".format(ci, pageNumber)
                        val props = if (isCover) " properties=\"cover-image\"" else ""
                        imageItems.append("<item id=\"$id\" href=\"$imgHref\" media-type=\"$mime\"$props/>")

                        val (width, height) = imageSize(bytes)
                        if (width > 0 && height > 0) {
                            val cls = "p%04d".format(pageNumber)
                            rules.append("div.$cls { background-image: url(\"$imgHref\"); ")
                                .append("padding-top: %.4f%%; }\n".format(Locale.ROOT, height * 100.0 / width))
                            body.append("<div class=\"pg $cls\"></div>")
                        } else {
                            body.append("<img class=\"page\" src=\"$imgHref\" alt=\"\"/>")
                        }
                    }
                } catch (e: Throwable) {
                    // A broken or password-protected chapter shouldn't stop the whole series
                    xLogE("EPUB export: skipped pages of ${chapter.name} in $title", e)
                }

                val head = xhtmlHead(chapter.name, if (rules.isEmpty()) "" else "<style>$rules</style>")
                zip.putText(
                    "OEBPS/$href",
                    "$head<body><section epub:type=\"chapter\">" +
                        "<h2 class=\"ch-title\">${chapter.name.esc()}</h2>\n$body</section></body></html>",
                )
                toc.add(href to chapter.name)
            }

            val uid = "urn:uuid:" + UUID.nameUUIDFromBytes("komikku-epub:${manga.id}".toByteArray())
            zip.putText(
                "OEBPS/nav.xhtml",
                xhtmlHead("Contents", "") +
                    "<body><nav epub:type=\"toc\" id=\"toc\"><h1>Contents</h1><ol>" +
                    toc.joinToString("") { (href, name) -> "<li><a href=\"$href\">${name.esc()}</a></li>" } +
                    "</ol></nav></body></html>",
            )
            zip.putText(
                "OEBPS/toc.ncx",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">" +
                    "<head><meta name=\"dtb:uid\" content=\"$uid\"/></head>" +
                    "<docTitle><text>${title.esc()}</text></docTitle><navMap>" +
                    toc.mapIndexed { i, (href, name) ->
                        "<navPoint id=\"n${i + 1}\" playOrder=\"${i + 1}\"><navLabel><text>${name.esc()}</text>" +
                            "</navLabel><content src=\"$href\"/></navPoint>"
                    }.joinToString("") +
                    "</navMap></ncx>",
            )
            zip.putText(
                "OEBPS/content.opf",
                "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"uid\">" +
                    "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
                    "<dc:identifier id=\"uid\">$uid</dc:identifier>" +
                    "<dc:title>${title.esc()}</dc:title>" +
                    (manga.author?.takeIf { it.isNotBlank() }?.let { "<dc:creator>${it.esc()}</dc:creator>" } ?: "") +
                    "<dc:language>en</dc:language>" +
                    "<meta property=\"dcterms:modified\">${modifiedTimestamp()}</meta>" +
                    (if (coverHref != null) "<meta name=\"cover\" content=\"cover-img\"/>" else "") +
                    "</metadata><manifest>" +
                    "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>" +
                    "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>" +
                    "<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>" +
                    toc.joinToString("") { (href, _) ->
                        val id = href.removeSuffix(".xhtml")
                        "<item id=\"$id\" href=\"$href\" media-type=\"application/xhtml+xml\"/>"
                    } +
                    imageItems +
                    "</manifest><spine toc=\"ncx\">" +
                    toc.joinToString("") { (href, _) -> "<itemref idref=\"${href.removeSuffix(".xhtml")}\"/>" } +
                    "</spine></package>",
            )
        }
    }

    /** Calls [block] with the bytes of each page of a downloaded chapter, in reading order. */
    private fun forEachImage(file: UniFile, block: (ByteArray) -> Unit) {
        if (file.isDirectory) {
            file.listFiles().orEmpty()
                .filter { !it.isDirectory && ImageUtil.isImage(it.name) { it.openInputStream() } }
                .sortedWith { a, b -> a.name.orEmpty().compareToCaseInsensitiveNaturalOrder(b.name.orEmpty()) }
                .forEach { image -> image.openInputStream().use { block(it.readBytes()) } }
            return
        }
        file.archiveReader(context).use { reader ->
            if (reader.wrongPassword == true) error("Incorrect archive password")
            val names = reader.useEntries { entries ->
                entries
                    .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .map { it.name }
                    .toList()
            }.sortedWith { a, b -> a.compareToCaseInsensitiveNaturalOrder(b) }
            names.forEach { name -> reader.getInputStream(name)?.use { block(it.readBytes()) } }
        }
    }

    private fun imageSize(bytes: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth to options.outHeight
    }

    private fun ZipOutputStream.putStored(name: String, data: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            compressedSize = data.size.toLong()
            crc = CRC32().apply { update(data) }.value
        }
        putNextEntry(entry)
        write(data)
        closeEntry()
    }

    private fun ZipOutputStream.putText(name: String, text: String) {
        putNextEntry(ZipEntry(name).apply { method = ZipEntry.DEFLATED })
        write(text.toByteArray())
        closeEntry()
    }

    private fun xhtmlHead(title: String, extra: String) =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" lang=\"en\">\n" +
            "<head><meta charset=\"utf-8\"/><title>${title.esc()}</title>" +
            "<link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/>$extra</head>\n"

    private fun modifiedTimestamp(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    private fun String.esc(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    companion object {
        // Exports write whole series, so run them one at a time
        private val mutex = Mutex()

        private val STYLE_CSS = """
            |body { margin: 0; padding: 0; }
            |div.pg { display: block; width: 100%; height: 0; margin: 0; border: 0; line-height: 0;
            |  background-repeat: no-repeat; background-size: 100% 100%; background-position: top left; }
            |img.page { display: block; width: 100%; height: auto; margin: 0; }
            |.ch-title { text-align: center; font-family: sans-serif; margin: 1.5em 0 0.8em; }
            |
        """.trimMargin()
    }
}
