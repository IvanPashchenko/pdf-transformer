import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString

var TRACE_ENABLED = false

@Suppress("UNUSED_PARAMETER")
fun main(args: Array<String>) {
    val parser = ArgParser("pdf-transformer")
    val crop = Rect(120, 63, 450, 795)

    val input by parser.argument(ArgType.String)
    val output by parser.argument(ArgType.String)
    val trace by parser.option(ArgType.Boolean)

    parser.parse(args)

    println("Source: $input")
    println("Destination: $output")
    TRACE_ENABLED = trace ?: false

    val transformer = PdfTransformer(
        Path.of(input),
        Path.of(output),
        946,
        crop,
        setOf(0, 1, 5),
    )

    transformer.makeSplitPages()
    transformer.mergePages()
}

class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

class PdfTransformer(
    private val sourcePdf: Path,
    private val destPdf: Path,
    private val lastPage: Int,
    private val crop: Rect,
    private val noTransformPages: Set<Int>
) {
    private val workDir: Path = Files.createTempDirectory("")

    init {
        println("Working in ${workDir.absolutePathString()}")
    }

    fun makeSplitPages() {
        val progress = AtomicInteger(0)
        val workload = (1..lastPage).toList()
        workload.parallelStream().forEach { page ->
            for (i in 1..GS_RETRY_COUNT) {
                try {
                    extractPageWithProgress(page, progress)
                    break
                } catch (e: Throwable) {
                    if (i < GS_RETRY_COUNT)
                        println("Failed to process page $page, retrying")
                    else {
                        println("Failed to process page in $GS_RETRY_COUNT attempts")
                        throw e
                    }
                }
            }
        }
    }

    private fun extractPageWithProgress(page: Int, progress: AtomicInteger) {
        val pagePdf = workDir.resolve("$page.pdf")

        val cropbox = if (noTransformPages.contains(page)) null else crop
        extractPage(page, pagePdf, cropbox)

        val pagesDone = progress.incrementAndGet()

        val ratioDone = pagesDone.toDouble() / lastPage
        println("$pagesDone/$lastPage (${NumberFormat.getPercentInstance().format(ratioDone)}) (p. $page)")
    }

    fun mergePages() {
        println("Merging...")
        val pages = (1..lastPage).toList().joinToString(" ") { "$it.pdf" }
        runProcess(
            listOf(gs, "-dBATCH", "-dNOPAUSE", "-q", "-sDEVICE=pdfwrite", "-sOutputFile=$destPdf", pages),600)
    }

    private fun extractPage(page: Int, pagePdf: Path, crop: Rect?) {
        if (crop == null) {
            val extractCommand = listOf(
                gs, "-q", "-sDEVICE=pdfwrite",
                "-dNOPAUSE", "-dBATCH", "-dSAFER",
                "-dFirstPage=$page", "-dLastPage=$page",
                "-o", pagePdf.toString(),
                "-f", sourcePdf.toString()
            )
            runProcess(extractCommand, 60 )
            return
        }

        val cropWidth = crop.right - crop.left
        val cropHeight = crop.bottom - crop.top

        runProcess(listOf(
            gs, "-q", "-sDEVICE=pdfwrite",
            "-dNOPAUSE", "-dBATCH", "-dSAFER",
            "-dFirstPage=$page", "-dLastPage=$page",
            "-dDEVICEWIDTHPOINTS=$cropWidth", "-dDEVICEHEIGHTPOINTS=$cropHeight", "-dFIXEDMEDIA",
            "-c", "<</PageOffset [-${crop.left} ${crop.top}]>> setpagedevice",
            "-o", pagePdf.toString(),
            "-f", sourcePdf.toString(),
        ), 60)
    }

    private fun runProcess(command: List<String>, timeoutSec: Int) {
        if (TRACE_ENABLED)
            println(command.joinToString(" "))

        val pb = ProcessBuilder(command)
            .directory(workDir.toFile())
            .inheritIO()

        val process = pb.start()
        if (!process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) error("too long")
        require(process.exitValue() == 0)
    }

    companion object {
        private val gs =
            if (System.getProperty("os.name").startsWith("win", true)) "C:/Program Files/gs/gs9.54.0/bin/gswin64c.exe"
            else "gs"
        const val GS_RETRY_COUNT = 5
    }
}