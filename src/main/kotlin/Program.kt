import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

fun main(args: Array<String>) {
    val transformer = PdfTransformer(
        Path.of("/home/haze/Downloads/convert/orig.pdf"),
        Path.of("/home/haze/Downloads/convert/merged.pdf"),
        945,
        300,
        "-crop +500+685 -crop -600-685 +repage",
        setOf(0, 1, 5)
    )

    transformer.makeSplitPages()
    transformer.mergePages()
}

class PdfTransformer(
    private val sourcePdf: Path,
    private val destPdf: Path,
    private val lastPage: Int,
    private val density: Int,
    private val transform: String,
    private val noTransformPages: Set<Int>
) {
    private val workDir: Path = Files.createTempDirectory("")

    fun makeSplitPages() {
        val progress = AtomicInteger(0)
        val workload = (0..lastPage).toList()
        workload.parallelStream().forEach { page ->
            val pagePdf = workDir.resolve("$page.pdf")
            if (noTransformPages.contains(page))
                extractPage(page, "", pagePdf)
            else
                extractPage(page, transform, pagePdf)

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

    fun extractPage(page: Int, transform: String, pagePdf: Path) = runProcess("convert $transform -density $density $sourcePdf[$page] $pagePdf", 60)

    private fun runProcess(command: String, timeoutSec: Int) {
        val pb = ProcessBuilder(command.split(Regex("\\s+")))
            .directory(workDir.toFile())
            .inheritIO()

        val process = pb.start()
        if (!process.waitFor(timeoutSec.toLong(), TimeUnit.SECONDS)) error("too long")
        require(process.exitValue() == 0)
    }
}