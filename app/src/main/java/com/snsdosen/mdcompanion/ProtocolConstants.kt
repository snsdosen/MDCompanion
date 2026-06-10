package com.snsdosen.mdcompanion.protocol

object Protocol {

    //Server
    const val MD_SERVER_URL: String = "https://raw.githubusercontent.com/snsdosen"
    const val MD_RAW_SERVER_URL: String = "https://github.com/snsdosen"


    //MediaDev protocol
    const val MD_REQUEST: String = "XMD"
    const val MD_REPLY: String = "RMD"
    const val MD_IDENTIFIER: String = "MediaDev"


    //Acquire information
    const val MD_GET_IDENTIFIER: String = "XMDI"
    const val MD_GET_MODEL: String = "XMDM"
    const val MD_GET_REVISION: String = "XMDH"
    const val MD_GET_FIRMWARE: String = "XMDV"
    const val MD_GET_PROTOCOL: String = "XMDP"
    const val MD_GET_PARTITION: String = "XMDU"


    //Response command codes
    const val MD_CMD_IDENTIFIER: String = "I"
    const val MD_CMD_MODEL: String = "M"
    const val MD_CMD_REVISION: String = "H"
    const val MD_CMD_FIRMWARE: String = "V"
    const val MD_CMD_PROTOCOL: String = "P"
    const val MD_CMD_WRITE: String = "W"
    const val MD_CMD_VERIFY: String = "F"
    const val MD_CMD_REBOOT: String = "R"
    const val MD_CMD_PARTITION: String = "U"
    const val MD_CMD_NOTIFICATION: String = "N" //Added in protocol version 1.1
    const val MD_CMD_REGISTER_EVT: String = "J" //Added in protocol version 1.1
    const val MD_CMD_EVENT: String = "L" //Added in protocol version 1.1


    //Binary signature flags
    const val MD_RELEASE_BUILD_SIGNATURE: Byte = 0x0
    const val MD_DEBUG_BUILD_SIGNATURE: Byte = 0x1


    //Set information
    const val MD_WRITE_DATA: String = "XMDW"
    const val MD_VERIFY_DATA: String = "XMDF"
    const val MD_REBOOT: String = "XMDR"
    const val MD_NOTIFICATION: String = "XMDN" //Added in protocol version 1.1


    //Transfer info
    const val MD_CHUNK_SIZE: Int = 96 //Writes on ESP32 are 16K, BT payload is 128, so we optimize


    //Responses
    const val MD_WRITE_ERROR: String = "E" //Undefined error
    const val MD_WRITE_OK: String = "K" //Flash successfully written
    const val MD_WRITE_WRONG_FORMAT: String = "W" //Binary is not meant for this device
    const val MD_WRITE_WRONG_CHECKSUM: String = "X" //Wrong checksum, resend data


    //Update states
    const val MD_UPDATE_DISABLED: Int = 0
    const val MD_UPDATE_ENABLED: Int = 1
    const val MD_UPDATE_CHECKING: Int = 2
    const val MD_UPDATE_AVAILABLE: Int = 3
    const val MD_UPDATE_UPDATING: Int = 4
    const val MD_UPDATE_DONE: Int = 5
}