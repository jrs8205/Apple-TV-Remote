package com.jrs8205.appletvremote.protocol.companion

import com.jrs8205.appletvremote.protocol.pairing.FakeAccessory

/** Manual-testing entry point: a fake Apple TV on localhost that a phone reaches through `adb reverse`. */
object FakeAppleTvMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val port = args.getOrNull(0)?.toInt() ?: 49152
        val pin = args.getOrNull(1) ?: "3939"
        val tv = FakeAppleTv(FakeAccessory(pin = pin), listenPort = port)
        println("fake apple tv listening on 127.0.0.1:${tv.port}, pin $pin")
        var seen = 0
        while (true) {
            Thread.sleep(500)
            val messages = tv.messages
            while (seen < messages.size) {
                val m = messages[seen++]
                println("${System.currentTimeMillis()} ${m.name} t=${m.messageType} ${m.content}")
            }
        }
    }
}
