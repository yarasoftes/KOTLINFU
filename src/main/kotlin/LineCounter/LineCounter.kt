package LineCounter
import java.io.File

fun main() {
    print("Введите путь к файлу: ")
    val file = File(readln())

    if (!file.exists()) {
        println("Файл не найден!")
        return
    }

    var total = 0
    var empty = 0
    var comments = 0
    var inComment = false

    file.forEachLine { line ->
        total++
        val t = line.trim()
        when {
            t.isEmpty() -> empty++
            inComment -> { comments++; if (t.contains("*/")) inComment = false }
            t.startsWith("/*") -> { comments++; if (!t.contains("*/")) inComment = true }
            t.startsWith("//") -> comments++
        }
    }

    println("Файл: ${file.name}")
    println("Всего: $total | Код: ${total - empty - comments} | Комм: $comments | Пусто: $empty")
}

// Введите полный путь к файлу, например: C:\Users\Имя\Desktop\урок.kt
// Или относительный путь, например: ./src/урок.kt
// Если файл не найден, программа завершится с ошибкой
