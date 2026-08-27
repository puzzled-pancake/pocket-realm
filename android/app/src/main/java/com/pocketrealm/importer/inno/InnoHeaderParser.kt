package com.pocketrealm.importer.inno

/**
 * Field-exact parser for the Inno 5.0.0 – 5.5.6 primary header stream and
 * the secondary data-entry block. Entry types the client lane does not model
 * (messages, permissions, types, components, tasks, directories, icons,
 * ini/registry/delete/run entries, wizard images) are still parsed
 * byte-for-byte — the stream cannot be skipped, only walked. A layout drift
 * anywhere throws instead of misframing what follows.
 */
internal class InnoHeaderParser internal constructor(
    internal val reader: InnoDataReader,
    private val version: InnoVersion,
) {
    private fun ge(v: Long) = version >= v
    private fun lt(v: Long) = version < v

    class ParsedHeader(
        val appName: String,
        val appVersionedName: String,
        val baseFilename: String,
        val slicesPerDisk: Int,
        val compression: Int,
        val passwordProtected: Boolean,
        val encrypted: Boolean,
        val fileEntries: List<ParsedFileEntry>,
        val dataEntryCount: Int,
    )

    class ParsedFileEntry(
        val destination: String,
        val location: Int,
        val type: Int,
    )

    /**
     * Parses header fields plus the language list and returns the installer's
     * codepage (0 for Unicode builds, which decode as UTF-16LE).
     */
    fun parseLanguageCodepage(): Long {
        val fields = parseHeaderFields()
        return parseLanguages(fields.languageCount).firstOrNull() ?: 1252L
    }

    /** Full parse of the primary stream: header, every entry list, images. */
    fun parse(): ParsedHeader {
        val fields = parseHeaderFields()
        val codepage = parseLanguages(fields.languageCount).firstOrNull() ?: 1252L
        repeat(messageCount) { parseMessage() }
        repeat(permissionCount) { reader.binaryString() }
        repeat(typeCount) { parseType() }
        repeat(componentCount) { parseComponent() }
        repeat(taskCount) { parseTask() }
        repeat(directoryCount) { parseDirectory() }
        val fileEntries = (0 until fileCount).map { parseFileEntry() }
        repeat(iconCount) { parseIcon() }
        repeat(iniCount) { parseIni() }
        repeat(registryCount) { parseRegistry() }
        repeat(deleteCount + uninstallDeleteCount) { parseDelete() }
        repeat(runCount + uninstallRunCount) { parseRun() }
        parseWizardImages(fields.compression, fields.encrypted)
        return ParsedHeader(
            fields.appName, fields.appVersionedName, fields.baseFilename,
            fields.slicesPerDisk, fields.compression, fields.passwordProtected,
            fields.encrypted, fileEntries, fields.dataEntryCount,
        )
    }

    /** Parses the secondary block's data (chunk location) entries. */
    fun parseDataEntries(reader: InnoDataReader, count: Int, compression: Int): List<InnoSetupReader.InnoDataEntry> {
        val entries = ArrayList<InnoSetupReader.InnoDataEntry>(count)
        repeat(count) {
            val firstSlice = reader.u32().toInt()
            reader.u32() // last slice
            val chunkOffset = reader.u32()
            val fileOffset = reader.u64()
            val fileSize = reader.u64()
            val chunkSize = reader.u64()
            val digest = if (lt(InnoVersion.V5_3_9)) {
                InnoSetupReader.Digest("MD5", reader.bytes(16))
            } else {
                InnoSetupReader.Digest("SHA-1", reader.bytes(20))
            }
            reader.i64() // filetime
            reader.u32() // file version ms
            reader.u32() // file version ls
            // Declaration order: VersionInfoValid, VersionInfoNotValid,
            // TimeStampInUTC, IsUninstallerExe, CallInstructionOptimized,
            // Touch, ChunkEncrypted, ChunkCompressed, SolidBreak.
            var callOptimized = false
            var encrypted = false
            var compressed = false
            val slots = ArrayList<Int>() // indices of bits present for this version
            val order = listOf(
                true, true, true, true, true, true, true, true,
                ge(InnoVersion.V5_1_13),
            )
            order.forEachIndexed { slot, present -> if (present) slots.add(slot) }
            val values = BooleanArray(order.size)
            var cursor = 0
            reader.flags(slots.size) { _, set -> values[slots[cursor++]] = set }
            callOptimized = values[4]
            encrypted = values[6]
            compressed = values[7]
            val filter = when {
                !callOptimized -> null
                lt(InnoVersion.V5_2_0) -> InnoCallFilterInputStream.Variant.PRE_5_2_0
                lt(InnoVersion.V5_3_9) -> InnoCallFilterInputStream.Variant.SINCE_5_2_0
                else -> InnoCallFilterInputStream.Variant.SINCE_5_3_9
            }
            entries.add(
                InnoSetupReader.InnoDataEntry(
                    firstSlice, chunkOffset, fileOffset, fileSize, chunkSize, digest,
                    filter, encrypted, if (compressed) compression else InnoSetupReader.COMPRESSION_STORED,
                ),
            )
        }
        return entries
    }

    // ---------------------------------------------------------------- header

    private class HeaderFields(
        val appName: String,
        val appVersionedName: String,
        val baseFilename: String,
        val slicesPerDisk: Int,
        val compression: Int,
        val passwordProtected: Boolean,
        val encrypted: Boolean,
        val languageCount: Int,
        val dataEntryCount: Int,
    )

    private var messageCount = 0
    private var permissionCount = 0
    private var typeCount = 0
    private var componentCount = 0
    private var taskCount = 0
    private var directoryCount = 0
    private var fileCount = 0
    private var dataEntryCountField = 0
    private var iconCount = 0
    private var iniCount = 0
    private var registryCount = 0
    private var deleteCount = 0
    private var uninstallDeleteCount = 0
    private var runCount = 0
    private var uninstallRunCount = 0

    private fun parseHeaderFields(): HeaderFields {
        val appName = reader.encodedString()
        val appVersionedName = reader.encodedString()
        reader.encodedString() // app id
        reader.encodedString() // copyright
        reader.encodedString() // publisher
        reader.encodedString() // publisher url
        if (ge(InnoVersion.V5_1_13)) reader.encodedString() // support phone
        reader.encodedString() // support url
        reader.encodedString() // updates url
        reader.encodedString() // app version
        reader.encodedString() // default dir name
        reader.encodedString() // default group name
        val baseFilename = reader.encodedString()
        if (lt(InnoVersion.V5_2_5)) {
            reader.binaryString(); reader.binaryString(); reader.binaryString()
        }
        reader.encodedString() // uninstall files dir
        reader.encodedString() // uninstall name
        reader.encodedString() // uninstall icon
        reader.encodedString() // app mutex
        reader.encodedString() // default user name
        reader.encodedString() // default user organisation
        reader.encodedString() // default serial
        if (lt(InnoVersion.V5_2_5)) reader.binaryString() // compiled script
        reader.encodedString() // readme
        reader.encodedString() // contact
        reader.encodedString() // comments
        reader.encodedString() // modify path
        if (ge(InnoVersion.V5_3_8)) reader.encodedString() // create uninstall reg key
        if (ge(InnoVersion.V5_3_10)) reader.encodedString() // uninstallable
        if (ge(InnoVersion.V5_5_0)) reader.encodedString() // close applications filter
        if (ge(InnoVersion.V5_5_6)) reader.encodedString() // setup mutex
        if (ge(InnoVersion.V5_2_5)) {
            reader.binaryString(); reader.binaryString(); reader.binaryString() // license texts
        }
        if (ge(InnoVersion.V5_2_1) && lt(InnoVersion.V5_3_10)) reader.binaryString() // signature
        if (ge(InnoVersion.V5_2_5)) reader.binaryString() // compiled script
        if (!version.unicode) reader.bytes(32) // DBCS lead-byte set

        val languageCount = reader.u32().toInt()
        messageCount = reader.u32().toInt()
        permissionCount = reader.u32().toInt()
        typeCount = reader.u32().toInt()
        componentCount = reader.u32().toInt()
        taskCount = reader.u32().toInt()
        directoryCount = reader.u32().toInt()
        fileCount = reader.u32().toInt()
        dataEntryCountField = reader.u32().toInt()
        iconCount = reader.u32().toInt()
        iniCount = reader.u32().toInt()
        registryCount = reader.u32().toInt()
        deleteCount = reader.u32().toInt()
        uninstallDeleteCount = reader.u32().toInt()
        runCount = reader.u32().toInt()
        uninstallRunCount = reader.u32().toInt()
        listOf(
            languageCount, messageCount, permissionCount, typeCount, componentCount,
            taskCount, directoryCount, fileCount, iconCount, iniCount, registryCount,
            deleteCount, uninstallDeleteCount, runCount, uninstallRunCount, dataEntryCountField,
        ).forEach { if (it < 0 || it > MAX_COUNT) throw InnoFormatException("implausible entry counts") }

        reader.windowsVersionRange()
        reader.u32() // back color
        reader.u32() // back color 2
        if (lt(InnoVersion.V5_5_7)) reader.u32() // image back color
        reader.bytes(if (lt(InnoVersion.V5_3_9)) 16 else 20) // password digest
        reader.bytes(8) // password salt
        reader.i64() // extra disk space required
        val slicesPerDisk = reader.u32().toInt().coerceAtLeast(1)
        reader.enum(2) // uninstall log mode
        reader.enum(2) // dir exists warning
        if (ge(InnoVersion.V5_3_7)) reader.enum(3) else reader.enum(2) // privileges
        reader.enum(2) // show language dialog
        reader.enum(2) // language detection
        val compression = if (ge(InnoVersion.V5_3_9)) reader.enum(4) else reader.enum(3)
        if (compression == InnoSetupReader.COMPRESSION_BZIP2 ||
            compression == InnoSetupReader.COMPRESSION_LZMA2
        ) {
            throw InnoFormatException("bzip2/lzma2-compressed installers are not supported")
        }
        if (ge(InnoVersion.V5_1_0)) {
            reader.bytes(1) // architectures allowed
            reader.bytes(1) // architectures installed in 64-bit mode
        }
        if (ge(InnoVersion.V5_2_1) && lt(InnoVersion.V5_3_10)) {
            reader.u32(); reader.u32()
        }
        if (ge(InnoVersion.V5_3_3)) {
            reader.enum(2); reader.enum(2)
        }
        when {
            ge(InnoVersion.V5_5_0) -> reader.u64()
            ge(InnoVersion.V5_3_6) -> reader.u32()
        }

        val options = readSetupOptions()
        return HeaderFields(
            appName, appVersionedName, baseFilename, slicesPerDisk, compression,
            options[OPTION_PASSWORD] == true, options[OPTION_ENCRYPTION_USED] == true,
            languageCount, dataEntryCountField,
        )
    }

    /**
     * The setup-options bitfield in declaration order. Each slot knows whether
     * it exists for this data version; the reader consumes one bit per
     * existing slot, LSB first.
     */
    private fun readSetupOptions(): Map<Int, Boolean> {
        data class Slot(val id: Int, val present: Boolean)
        val slots = listOf(
            Slot(0, true), // disable startup prompt
            Slot(1, lt(InnoVersion.V5_3_10)), // uninstallable
            Slot(2, true), // create app dir
            Slot(3, lt(InnoVersion.V5_3_3)), // disable dir page
            Slot(4, lt(InnoVersion.V5_3_3)), // disable program group page
            Slot(5, true), // allow no icons
            Slot(6, true), // always restart
            Slot(7, true), // always use personal group
            Slot(8, true), // window visible
            Slot(9, true), // window show caption
            Slot(10, true), // window resizable
            Slot(11, true), // window start maximized
            Slot(12, true), // enable dir doesn't exist warning
            Slot(OPTION_PASSWORD, true), // password
            Slot(13, true), // allow root directory
            Slot(14, true), // disable finished page
            Slot(15, true), // changes associations (< 5.6.1)
            Slot(16, lt(InnoVersion.V5_3_8)), // create uninstall reg key
            Slot(17, true), // use previous app dir
            Slot(18, true), // back color horizontal
            Slot(19, true), // use previous group
            Slot(20, true), // update uninstall log app name
            Slot(21, true), // use previous setup type
            Slot(22, true), // disable ready memo
            Slot(23, true), // always show components list
            Slot(24, true), // flat components list
            Slot(25, true), // show component sizes
            Slot(26, true), // use previous tasks
            Slot(27, true), // disable ready page
            Slot(28, true), // always show dir on ready page
            Slot(29, true), // always show group on ready page
            Slot(30, true), // allow UNC path
            Slot(31, true), // user info page
            Slot(32, true), // use previous user info
            Slot(33, true), // uninstall restart computer
            Slot(34, true), // restart if needed by run
            Slot(35, true), // show tasks tree lines
            Slot(36, true), // allow cancel during install
            Slot(37, true), // wizard image stretch
            Slot(38, true), // append default dir name
            Slot(39, true), // append default group name
            Slot(OPTION_ENCRYPTION_USED, true), // encryption used
            Slot(40, ge(InnoVersion.V5_0_0)), // changes environment (>= 5.0.4)
            Slot(41, ge(InnoVersion.V5_1_7) && !version.unicode),
            Slot(42, ge(InnoVersion.V5_1_13)), // setup logging
            Slot(43, ge(InnoVersion.V5_2_1)), // signed uninstaller
            Slot(44, ge(InnoVersion.V5_3_8)), // use previous language
            Slot(45, ge(InnoVersion.V5_3_9)), // disable welcome page
            Slot(46, ge(InnoVersion.V5_5_0)), // close applications
            Slot(47, ge(InnoVersion.V5_5_0)), // restart applications
            Slot(48, ge(InnoVersion.V5_5_0)), // allow network drive
        )
        val present = slots.filter { it.present }
        val values = HashMap<Int, Boolean>()
        var cursor = 0
        reader.flags(present.size) { _, set ->
            values[present[cursor++].id] = set
        }
        return values
    }

    // ------------------------------------------------------- entries (5.x)

    /** Per-language codepages; the first entry wins upstream. */
    private fun parseLanguages(count: Int): List<Long> {
        val codepages = ArrayList<Long>(count)
        repeat(count) {
            reader.encodedString() // name
            reader.encodedString() // language name
            reader.encodedString() // dialog font
            reader.encodedString() // title font
            reader.encodedString() // welcome font
            reader.encodedString() // copyright font
            reader.encodedString() // data
            reader.binaryString() // license text
            reader.binaryString() // info before
            reader.binaryString() // info after
            reader.u32() // language id
            val codepage = if (version.unicode) {
                if (lt(InnoVersion.V5_3_0)) reader.u32()
                UNICODE_CODEPAGE
            } else {
                reader.u32()
            }
            reader.u32() // dialog font size
            reader.u32() // title font size
            reader.u32() // welcome font size
            reader.u32() // copyright font size
            if (ge(InnoVersion.V5_2_3)) reader.bool() // right to left
            codepages.add(if (codepage == 0L) 1252L else codepage)
        }
        return codepages
    }

    private fun parseMessage() {
        reader.encodedString() // name
        reader.binaryString() // value; body stays undecoded
        reader.i32() // language index
    }

    private fun parseType() {
        reader.encodedString() // name
        reader.encodedString() // description
        reader.encodedString() // languages
        reader.encodedString() // check
        reader.windowsVersionRange()
        reader.bytes(1) // flags
        reader.enum(3) // setup type
        reader.u64() // size
    }

    private fun parseComponent() {
        reader.encodedString() // name
        reader.encodedString() // description
        reader.encodedString() // types
        reader.encodedString() // languages
        reader.encodedString() // check
        reader.u64() // extra disk space
        reader.i32() // level
        reader.bool() // used
        reader.windowsVersionRange()
        reader.bytes(1) // flags (>= 4.2.3 map)
        reader.u64() // size
    }

    private fun parseTask() {
        reader.encodedString() // name
        reader.encodedString() // description
        reader.encodedString() // group description
        reader.encodedString() // components
        reader.encodedString() // languages
        reader.encodedString() // check
        reader.i32() // level
        reader.bool() // used
        reader.windowsVersionRange()
        reader.bytes(1) // flags
    }

    private fun parseDirectory() {
        reader.encodedString() // name
        parseConditionData()
        reader.u32() // attributes
        reader.windowsVersionRange()
        reader.i16() // permission
        reader.bytes(1) // flags (>= 5.2.0 map)
    }

    private fun parseFileEntry(): ParsedFileEntry {
        reader.encodedString() // source
        val destination = reader.encodedString()
        reader.encodedString() // install font name
        if (ge(InnoVersion.V5_2_5)) reader.encodedString() // strong assembly name
        parseConditionData()
        reader.windowsVersionRange()
        val location = reader.u32().toInt()
        reader.u32() // attributes
        reader.u64() // external size
        reader.i16() // permission
        var fileBits = 24
        if (ge(InnoVersion.V5_0_3)) fileBits++
        if (ge(InnoVersion.V5_1_0)) fileBits++
        if (ge(InnoVersion.V5_1_2)) fileBits += 2
        if (ge(InnoVersion.V5_2_0)) fileBits += 3
        if (ge(InnoVersion.V5_2_5)) fileBits++
        skipFlags(fileBits)
        val type = reader.enum(1) // user file / uninstaller exe
        return ParsedFileEntry(destination, location, type)
    }

    private fun parseIcon() {
        reader.encodedString() // name
        reader.encodedString() // filename
        reader.encodedString() // parameters
        reader.encodedString() // working dir
        reader.encodedString() // icon file
        reader.encodedString() // comment
        parseConditionData()
        if (ge(InnoVersion.V5_3_5)) reader.encodedString() // app user model id
        reader.windowsVersionRange()
        reader.i32() // icon index
        reader.i32() // show command
        reader.enum(2) // close on exit
        reader.u16() // hotkey
        var iconBits = 3
        if (ge(InnoVersion.V5_0_3)) iconBits++
        if (ge(InnoVersion.V5_4_2)) iconBits++
        if (ge(InnoVersion.V5_5_0)) iconBits++
        skipFlags(iconBits)
    }

    private fun parseIni() {
        reader.encodedString() // ini file
        reader.encodedString() // section
        reader.encodedString() // key
        reader.encodedString() // value
        parseConditionData()
        reader.windowsVersionRange()
        reader.bytes(1) // flags
    }

    private fun parseRegistry() {
        reader.encodedString() // key
        reader.encodedString() // name
        reader.binaryString() // value
        parseConditionData()
        reader.windowsVersionRange()
        reader.u32() // hive
        reader.i16() // permission
        if (ge(InnoVersion.V5_2_5)) reader.enum(7) else reader.enum(6)
        skipFlags(if (ge(InnoVersion.V5_1_0)) 12 else 10)
    }

    private fun parseDelete() {
        reader.encodedString() // name
        parseConditionData()
        reader.windowsVersionRange()
        reader.enum(2) // target type
    }

    private fun parseRun() {
        reader.encodedString() // name
        reader.encodedString() // parameters
        reader.encodedString() // working dir
        reader.encodedString() // run once id
        reader.encodedString() // status message
        if (ge(InnoVersion.V5_1_13)) reader.encodedString() // verb
        reader.encodedString() // description
        parseConditionData()
        reader.windowsVersionRange()
        reader.i32() // show command
        reader.enum(3) // wait condition
        val runBits = 7 + (if (ge(InnoVersion.V5_1_0)) 2 else 0) + (if (ge(InnoVersion.V5_2_0)) 1 else 0)
        skipFlags(runBits)
    }

    /** components, tasks, languages, check, after install, before install. */
    private fun parseConditionData() {
        repeat(6) { reader.encodedString() }
    }

    private fun skipFlags(bitCount: Int) {
        reader.flags(bitCount) { _, _ -> }
    }

    private fun parseWizardImages(compression: Int, encrypted: Boolean) {
        // One big and one small image before 5.6.0.
        reader.binaryString()
        reader.binaryString()
        if (compression == InnoSetupReader.COMPRESSION_ZLIB) {
            reader.binaryString() // decompressor dll
        }
        if (encrypted) {
            reader.binaryString() // decrypt dll
        }
    }

    private companion object {
        const val OPTION_PASSWORD = 100
        const val OPTION_ENCRYPTION_USED = 101
        const val UNICODE_CODEPAGE = -1L
        const val MAX_COUNT = 200_000
    }
}
