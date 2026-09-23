package com.jrs8205.appletvremote.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProtocolPurityTest {

    @Test
    fun protocolPackageHasNoAndroidImports() {
        val root = File("src/main/java/com/jrs8205/appletvremote/protocol")
        assertTrue("protocol source directory missing: ${root.absolutePath}", root.isDirectory)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> line.startsWith("import android") }
                    .map { (index, line) -> "${file.relativeTo(root)}:${index + 1}: $line" }
            }
            .toList()
        assertEquals("protocol code must stay pure JVM", emptyList<String>(), offenders)
    }
}
