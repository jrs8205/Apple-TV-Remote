package com.jrs8205.appletvremote.protocol.companion

enum class FrameType(val code: Int) {
    UNKNOWN(0x00),
    NO_OP(0x01),
    PS_START(0x03),
    PS_NEXT(0x04),
    PV_START(0x05),
    PV_NEXT(0x06),
    U_OPACK(0x07),
    E_OPACK(0x08),
    P_OPACK(0x09),
    PA_REQ(0x0A),
    PA_RSP(0x0B),
    SESSION_START_REQUEST(0x10),
    SESSION_START_RESPONSE(0x11),
    SESSION_DATA(0x12),
    FAMILY_IDENTITY_REQUEST(0x20),
    FAMILY_IDENTITY_RESPONSE(0x21),
    FAMILY_IDENTITY_UPDATE(0x22),
    ;

    companion object {
        fun fromCode(code: Int): FrameType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
