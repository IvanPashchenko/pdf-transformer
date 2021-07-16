import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString

@Suppress("UNUSED_PARAMETER")
fun main(args: Array<String>) {
    val crop = Rect(120, 63, 450, 795)

    val transformer = PdfTransformer(
        Path.of("C:/Users/root/Downloads/orig.pdf"),
        Path.of("C:/Users/root/Downloads/merged.pdf"),
        946,
        crop,
        setOf(0, 1, 5)
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
        val pagePdf = workDir.resolve("$page.pdf.orig")

        val cropbox = if (noTransformPages.contains(page)) null else crop
        extractPage(page, pagePdf, cropbox)

        val pagesDone = progress.incrementAndGet()

        val ratioDone = pagesDone.toDouble() / lastPage
        println("$pagesDone/$lastPage (${NumberFormat.getPercentInstance().format(ratioDone)}) (p. $page)")
    }

    fun mergePages() {
        println("Merging...")
        val pages = (1..lastPage).toList().joinToString(" ") { workDir.resolve("$it.pdf").absolutePathString() }
        runProcess("$gs -dBATCH -dNOPAUSE -q -sDEVICE=pdfwrite -sOutputFile=$destPdf $pages", 600)
    }

    private fun extractPage(page: Int, pagePdf: Path, crop: Rect?) {
        if (crop == null) {
            val extractCommand = "$gs -q -sDEVICE=pdfwrite -dNOPAUSE -dBATCH -dSAFER -dFirstPage=$page -dLastPage=$page -o $pagePdf -f $sourcePdf"
            runProcess(extractCommand, 60 )
            return
        }

        val cropWidth = crop.right - crop.left
        val cropHeight = crop.bottom - crop.top

        runProcess(listOf(
            gs,
            "-q",
            "-o", pagePdf.toString(),
            "-sDEVICE=pdfwrite",
            "-dNOPAUSE", "-dBATCH", "-dSAFER",
            "-dFirstPage=$page", "-dLastPage=$page",
            "-dDEVICEWIDTHPOINTS=$cropWidth", "-dDEVICEHEIGHTPOINTS=$cropHeight", "-dFIXEDMEDIA",
            "-c", "<</PageOffset [-${crop.left} ${crop.top}]>> setpagedevice",
            "-f", sourcePdf.toString(),
        ), 60)
    }

    private val argumentsRegex = Regex("\\s+")

    private fun runProcess(command: String, timeoutSec: Int) = runProcess(command.split(argumentsRegex), timeoutSec)
    private fun runProcess(command: List<String>, timeoutSec: Int) {
//        println(command.joinToString(" "))

        val pb = ProcessBuilder(command)
            .directory(workDir.toFile())
            .inheritIO()

        val process = pb.start()
        if (!process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) error("too long")
        require(process.exitValue() == 0)
    }

    companion object {
        private const val gs = "C:/Program Files/gs/gs9.54.0/bin/gswin64c.exe"
        const val GS_RETRY_COUNT = 5
    }
}