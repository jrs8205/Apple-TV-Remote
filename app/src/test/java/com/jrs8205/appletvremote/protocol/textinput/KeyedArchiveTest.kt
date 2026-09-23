package com.jrs8205.appletvremote.protocol.textinput

import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.dd.plist.UID
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedArchiveTest {

    private val sessionUuid = ByteArray(16) { (0x10 + it).toByte() }

    private fun resource(name: String): ByteArray = KeyedArchiveTest::class.java.getResourceAsStream("/rti/$name")!!.use { it.readBytes() }

    @Test
    fun parsesKeyboardStateFromTheTv() {
        val state = KeyedArchive.parseState(resource("ti_state.bplist"))
        assertArrayEquals(sessionUuid, state.sessionUuid)
        assertEquals("typed so far", state.currentText)
        assertEquals("Search", state.prompt)
    }

    @Test
    fun parsesStateWhenSessionIdIsPlainData() {
        val root = NSDictionary().apply {
            put("\$version", 100000)
            put("\$archiver", "NSKeyedArchiver")
            put("\$top", NSDictionary().apply { put("sessionUUID", UID("", byteArrayOf(1))) })
            put("\$objects", NSArray(NSString("\$null"), NSData(sessionUuid)))
        }
        val state = KeyedArchive.parseState(com.dd.plist.BinaryPropertyListWriter.writeToArray(root))
        assertArrayEquals(sessionUuid, state.sessionUuid)
        assertNull(state.currentText)
        assertNull(state.prompt)
    }

    @Test
    fun garbageYieldsEmptyState() {
        val state = KeyedArchive.parseState(byteArrayOf(1, 2, 3))
        assertNull(state.sessionUuid)
        assertNull(state.currentText)
    }

    @Test
    fun insertOperationMatchesTheReferenceStructure() {
        val ours = PropertyListParser.parse(KeyedArchive.insertOperation(sessionUuid, "Hei ä")) as NSDictionary
        val reference = PropertyListParser.parse(resource("rti_insert.bplist")) as NSDictionary
        assertEquals(describe(reference), describe(ours))
    }

    @Test
    fun clearOperationMatchesTheReferenceStructure() {
        val ours = PropertyListParser.parse(KeyedArchive.clearOperation(sessionUuid)) as NSDictionary
        val reference = PropertyListParser.parse(resource("rti_clear.bplist")) as NSDictionary
        assertEquals(describe(reference), describe(ours))
    }

    @Test
    fun insertOperationRoundTripsThroughOurOwnParser() {
        val archive = KeyedArchive.insertOperation(sessionUuid, "abc")
        val top = KeyedArchive.resolve(archive, listOf("textOperations", "keyboardOutput", "insertionText"))
        assertEquals("abc", top)
        val uuid = KeyedArchive.resolve(archive, listOf("textOperations", "targetSessionUUID", "NS.uuidbytes"))
        assertTrue(uuid is ByteArray && uuid.contentEquals(sessionUuid))
    }

    /** Renders a keyed archive with every UID followed, so two encoders can be compared structurally. */
    private fun describe(root: NSDictionary): String {
        val objects = (root["\$objects"] as NSArray).array
        fun render(value: Any?): String = when (value) {
            is UID -> render(objects[value.bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }])
            is NSDictionary -> value.keys.sorted().joinToString(prefix = "{", postfix = "}") { "$it=" + render(value[it]) }
            is NSArray -> value.array.joinToString(prefix = "[", postfix = "]") { render(it) }
            is NSString -> "\"${value.content}\""
            is NSData -> "data(${value.bytes().toHexString()})"
            else -> value.toString()
        }
        return "archiver=${(root["\$archiver"] as NSString).content} top=" + render(root["\$top"])
    }
}
