import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString

fun main(args: Array<String>) {
    val args = listOf(
        "/home/haze/Downloads/convert/orig.pdf",
        "/home/haze/Downloads/convert/merged.pdf",
        "945",
        "300",
        "-crop +500+685 -crop -600-685 +repage",
        "0,1,5"
    )

    val transformer = PdfTransformer(
        Path.of("/home/haze/Downloads/convert/orig.pdf"),
        Path.of("/home/haze/Downloads/convert/merged.pdf"),
        945,
        Dimensions("594.75", "795"),
        Rect(1000, 0, 0, 0),
        setOf(0, 1, 5)
    )

    transformer.extractPage(45, Path.of("/home/haze/github/pdf-transformer/45.pdf"), Rect(1000, 1000, 1000, 1000))

//    transformer.makeSplitPages()
//    transformer.mergePages()
}

class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)
class Dimensions(val width: String, val height: String)

class PdfTransformer(
    private val sourcePdf: Path,
    private val destPdf: Path,
    private val lastPage: Int,
    private val dimensions: Dimensions,
    private val crop: Rect,
    private val noTransformPages: Set<Int>
) {
    private val workDir: Path = Files.createTempDirectory("")

    init {
        println("Working in ${workDir.absolutePathString()}")
    }

    fun makeSplitPages() {
        val progress = AtomicInteger(0)
        val workload = (0..lastPage).toList()
        workload.parallelStream().forEach { page ->
            val pagePdf = workDir.resolve("$page.pdf.orig")

            val cropbox = if (noTransformPages.contains(page)) null else crop
            extractPage(page, pagePdf, cropbox)

            val pagesDone = progress.incrementAndGet()
            val pageCount = lastPage + 1

            val ratioDone = pagesDone.toDouble() / pageCount

            println("$pagesDone/$pageCount (${NumberFormat.getPercentInstance().format(ratioDone)})")
        }
    }

    fun mergePages() {
        println("Merging...")
        val pages = (0..lastPage).toList().joinToString(" ") { "$it.pdf" }
        runProcess("gs -dBATCH -dNOPAUSE -q -sDEVICE=pdfwrite -sOutputFile=$destPdf $pages", 600)
    }

    private fun extractPageOld(page: Int, transform: String, pagePdf: Path) = runProcess("convert $transform -density $dimensions $sourcePdf[$page] $pagePdf", 60)

    fun extractPage(page: Int, pagePdf: Path, crop: Rect?) {
        if (crop == null) {
            val extractCommand = "gs -sDEVICE=pdfwrite -dNOPAUSE -dBATCH -dSAFER -dFirstPage=$page -dLastPage=$page -sOutputFile=$pagePdf $sourcePdf"
            runProcess(extractCommand, 60 )
            return
        }

        runProcess(listOf(
            "gs",
            "-o", pagePdf.absolutePathString(),
            "-sDEVICE=pdfwrite",
            "-dNOPAUSE", "-dBATCH", "-dSAFER",
            "-dFirstPage=$page", "-dLastPage=$page",
            "-dDEVICEWIDTHPOINTS=200", "-dDEVICEHEIGHTPOINTS=250", "-dFIXEDMEDIA",
            "-c", "<</PageOffset [-21 -32]>> setpagedevice",
            "-f", sourcePdf.absolutePathString(),
        ), 60)
    }

    private val argumentsRegex = Regex("\\s+")

    private fun runProcess(command: String, timeoutSec: Int) = runProcess(command.split(argumentsRegex), timeoutSec)
    private fun runProcess(command: List<String>, timeoutSec: Int) {
        val pb = ProcessBuilder(command)
            .directory(workDir.toFile())
            .inheritIO()

        val process = pb.start()
        if (!process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) error("too long")
        require(process.exitValue() == 0)
    }
}