package com.jrs8205.appletvremote.protocol.textinput

import com.dd.plist.BinaryPropertyListWriter
import com.dd.plist.NSArray
import com.dd.plist.NSData
import com.dd.plist.NSDictionary
import com.dd.plist.NSNumber
import com.dd.plist.NSObject
import com.dd.plist.NSString
import com.dd.plist.PropertyListParser
import com.dd.plist.UID

/** What the Apple TV reports about its on-screen keyboard. */
class TextInputState(val sessionUuid: ByteArray?, val prompt: String?, val currentText: String?)

/**
 * Builds and reads the NSKeyedArchiver plists carried in `_tiD`. The structures mirror what the
 * remote text input service expects: an `RTITextOperations` object with a target session and a
 * `TIKeyboardOutput` describing the change.
 */
object KeyedArchive {

    fun parseState(archive: ByteArray): TextInputState {
        val session = resolve(archive, listOf("sessionUUID"))
        val sessionBytes = when (session) {
            is ByteArray -> session
            is Map<*, *> -> session["NS.uuidbytes"] as? ByteArray
            else -> null
        }
        return TextInputState(
            sessionUuid = sessionBytes,
            prompt = resolve(archive, listOf("documentTraits", "prompt")) as? String,
            currentText = resolve(archive, listOf("documentState", "docSt", "contextBeforeInput")) as? String,
        )
    }

    fun clearOperation(sessionUuid: ByteArray): ByteArray = archive(
        top = dict("textOperations" to uid(1)),
        objects = listOf(
            NSString("\$null"),
            dict("\$class" to uid(7), "targetSessionUUID" to uid(5), "keyboardOutput" to uid(2), "textToAssert" to uid(4)),
            dict("\$class" to uid(3)),
            classEntry("TIKeyboardOutput"),
            NSString(""),
            dict("NS.uuidbytes" to NSData(sessionUuid), "\$class" to uid(6)),
            classEntry("NSUUID"),
            classEntry("RTITextOperations"),
        ),
    )

    fun insertOperation(sessionUuid: ByteArray, text: String): ByteArray = archive(
        top = dict("textOperations" to uid(1)),
        objects = listOf(
            NSString("\$null"),
            dict("keyboardOutput" to uid(2), "\$class" to uid(7), "targetSessionUUID" to uid(5)),
            dict("insertionText" to uid(3), "\$class" to uid(4)),
            NSString(text),
            classEntry("TIKeyboardOutput"),
            dict("NS.uuidbytes" to NSData(sessionUuid), "\$class" to uid(6)),
            classEntry("NSUUID"),
            classEntry("RTITextOperations"),
        ),
    )

    /**
     * Follows [path] from `$top`, dereferencing UIDs into `$objects`. Returns a String, ByteArray,
     * Long, Double, Boolean, List or Map (for dictionaries that were not descended into), or null.
     */
    fun resolve(archive: ByteArray, path: List<String>): Any? {
        val root = try {
            PropertyListParser.parse(archive) as? NSDictionary
        } catch (_: Exception) {
            null
        } ?: return null
        val objects = (root["\$objects"] as? NSArray)?.array ?: return null
        var element: NSObject? = root["\$top"] ?: return null
        for (key in path) {
            val dictionary = element as? NSDictionary ?: return null
            element = dictionary[key] ?: return null
            if (element is UID) element = objects.getOrNull(element.index()) ?: return null
        }
        return element?.toKotlin(objects)
    }

    private fun UID.index(): Int = bytes.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }

    private fun NSObject.toKotlin(objects: Array<NSObject>): Any? = when (this) {
        is NSString -> content
        is NSData -> bytes()
        is NSNumber -> when {
            isBoolean -> boolValue()
            isInteger -> longValue()
            else -> doubleValue()
        }
        is NSArray -> array.map { it.toKotlin(objects) }
        is NSDictionary -> hashMap.mapValues { (_, value) ->
            val target = if (value is UID) objects.getOrNull(value.index()) else value
            target?.toKotlin(objects)
        }
        is UID -> objects.getOrNull(index())?.toKotlin(objects)
        else -> toString()
    }

    private fun archive(top: NSDictionary, objects: List<NSObject>): ByteArray {
        val root = NSDictionary()
        root.put("\$version", NSNumber(100000))
        root.put("\$archiver", NSString("RTIKeyedArchiver"))
        root.put("\$top", top)
        root.put("\$objects", NSArray(*objects.toTypedArray()))
        return BinaryPropertyListWriter.writeToArray(root)
    }

    private fun dict(vararg entries: Pair<String, NSObject>): NSDictionary =
        NSDictionary().apply { entries.forEach { (key, value) -> put(key, value) } }

    private fun classEntry(name: String): NSDictionary =
        dict("\$classname" to NSString(name), "\$classes" to NSArray(NSString(name), NSString("NSObject")))

    private fun uid(index: Int): UID = UID("", byteArrayOf(index.toByte()))
}
