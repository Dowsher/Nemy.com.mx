package org.pipalab.core
import java.io.Reader

object Csv {
    fun rows(reader: Reader): Sequence<List<String>> = sequence {
        val r = reader.buffered()
        val field = StringBuilder()
        val row = mutableListOf<String>()
        var quoted = false
        var pending = -2
        var seen = false
        while (true) {
            val ch = if (pending != -2) pending.also { pending = -2 } else r.read()
            if (ch == -1) {
                require(!quoted) { "CSV incompleto: comillas sin cerrar" }
                if (seen || field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); yield(row.toList()) }
                break
            }
            val c = ch.toChar()
            seen = true
            if (c == '"') {
                if (quoted) {
                    val next = r.read()
                    if (next == '"'.code) field.append('"') else { quoted = false; pending = next }
                } else if (field.isEmpty()) quoted = true else field.append(c)
            } else if (c == ',' && !quoted) {
                row.add(field.toString()); field.clear()
            } else if ((c == '\n' || c == '\r') && !quoted) {
                if (c == '\r') { val next = r.read(); if (next != '\n'.code) pending = next }
                row.add(field.toString()); yield(row.toList()); row.clear(); field.clear(); seen = false
            } else field.append(c)
        }
    }
    fun line(values: List<Any?>): String = values.joinToString(",") {
        val s = it?.toString() ?: ""
        if (s.any { c -> c == ',' || c == '"' || c == '\n' || c == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }
}
