package podschet

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class FileAnalysisResult(
    val fileName: String,
    val totalLines: Int,
    val emptyLines: Int,
    val codeLines: Int,
    val commentLines: Int
)

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        println("Пожалуйста, укажите путь к файлу")
        println("Использование: kotlin LineCounterKt <путь_к_файлу>")
        return
    }

    val filePath = args[0]
    val file = File(filePath)

    if (!file.exists()) {
        println("Ошибка: Файл '$filePath' не найден")
        return
    }

    if (!file.isFile) {
        println("Ошибка: '$filePath' не является файлом")
        return
    }

    val result = analyzeFile(file)

    // Вывод результатов в консоль
    printResults(result)

    // Сохранение результатов в JSON
    saveResultsAsJson(result)
}

fun analyzeFile(file: File): FileAnalysisResult {
    var totalLines = 0
    var emptyLines = 0
    var commentLines = 0
    var inMultiLineComment = false

    file.useLines { lines ->
        lines.forEach { line ->
            totalLines++
            val trimmedLine = line.trim()

            when {
                trimmedLine.isEmpty() -> emptyLines++

                inMultiLineComment -> {
                    commentLines++
                    if (trimmedLine.contains("*/")) {
                        inMultiLineComment = false
                    }
                }

                trimmedLine.startsWith("/*") -> {
                    commentLines++
                    if (!trimmedLine.contains("*/")) {
                        inMultiLineComment = true
                    }
                }

                trimmedLine.startsWith("//") -> commentLines++

                else -> { /* кодовая строка */ }
            }
        }
    }

    val codeLines = totalLines - emptyLines - commentLines

    return FileAnalysisResult(
        fileName = file.name,
        totalLines = totalLines,
        emptyLines = emptyLines,
        codeLines = codeLines,
        commentLines = commentLines
    )
}

fun printResults(result: FileAnalysisResult) {
    println("\n" + "=".repeat(50))
    println("АНАЛИЗ ФАЙЛА: ${result.fileName}")
    println("=".repeat(50))
    println("Всего строк:        ${result.totalLines}")
    println("Строк кода:         ${result.codeLines}")
    println("Строк комментариев: ${result.commentLines}")
    println("Пустых строк:       ${result.emptyLines}")
    println("=".repeat(50))

    // Процентное соотношение
    if (result.totalLines > 0) {
        println("\nПроцентное соотношение:")
        println("Код:          ${String.format("%.1f", result.codeLines * 100.0 / result.totalLines)}%")
        println("Комментарии:   ${String.format("%.1f", result.commentLines * 100.0 / result.totalLines)}%")
        println("Пустые строки: ${String.format("%.1f", result.emptyLines * 100.0 / result.totalLines)}%")
    }
    println()
}

fun saveResultsAsJson(result: FileAnalysisResult) {
    try {
        val json = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        val jsonString = json.encodeToString(result)
        val outputFile = File("analysis_result_${System.currentTimeMillis()}.json")
        outputFile.writeText(jsonString)

        println("Результаты сохранены в файл: ${outputFile.name}")
    } catch (e: Exception) {
        println("Ошибка при сохранении JSON: ${e.message}")
    }
}