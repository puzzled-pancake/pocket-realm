package com.pocketrealm.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException

class AddonArchiveValidatorTest {
    @Test fun `GitHub URL parser accepts only exact public repository URLs`() {
        assertEquals("owner/repo", GitHubRepoRef.parse("https://github.com/owner/repo.git").slug)
        assertThrows(IllegalArgumentException::class.java) { GitHubRepoRef.parse("http://github.com/owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { GitHubRepoRef.parse("https://example.com/owner/repo") }
        assertThrows(IllegalArgumentException::class.java) { GitHubRepoRef.parse("https://github.com/owner/repo/issues") }
    }

    @Test fun `valid repository root addon is accepted and wrapped`() {
        val archive = zip(
            "owner-addon-abc/Example.toc" to "## Interface: 11200\nExample.lua\n",
            "owner-addon-abc/Example.lua" to "print('ok')\n",
        )
        val result = AddonArchiveValidator().validate(archive, "DifferentRepositoryName")
        assertEquals(listOf("Example"), result.addonFolders)
        assertEquals(setOf("Example/Example.toc", "Example/Example.lua"),
            result.entries.map { it.relativeName }.toSet())
    }

    @Test fun `multiple repository root TOCs fail closed`() {
        val archive = zip(
            "wrapper/First.toc" to "## Interface: 11200\nFirst.lua\n",
            "wrapper/First.lua" to "-- first\n",
            "wrapper/Second.toc" to "## Interface: 11200\nSecond.lua\n",
            "wrapper/Second.lua" to "-- second\n",
        )
        assertThrows(IllegalArgumentException::class.java) {
            AddonArchiveValidator().validate(archive, "Repository")
        }
    }

    @Test fun `regular addon payloads are accepted regardless of filename extension`() {
        listOf("payload.EXE", "payload.DlL", "payload.so", "payload.so.1", "payload.DYLIB").forEach { payload ->
            val archive = zip(
                "root/Example.toc" to "## Interface: 11200\nExample.lua\n",
                "root/Example.lua" to "-- safe\n",
                "root/$payload" to "not executable bytes",
            )
            val result = AddonArchiveValidator().validate(archive, "Example")
            assertEquals(
                setOf("Example/Example.toc", "Example/Example.lua", "Example/$payload"),
                result.entries.map { it.relativeName }.toSet(),
            )
        }
    }

    @Test fun `absolute traversal and symlink entries fail closed`() {
        listOf("/root/evil.lua", "root/../evil.lua", "C:/root/evil.lua").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) {
                AddonArchiveValidator().validate(archive(
                    ArchiveRecord("root/Example.toc", "## Interface: 11200\nExample.lua\n"),
                    ArchiveRecord("root/Example.lua", "-- safe\n"),
                    ArchiveRecord(name, "-- unsafe\n"),
                ), "Example")
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            AddonArchiveValidator().validate(archive(
                ArchiveRecord("root/Example.toc", "## Interface: 11200\nExample.lua\n"),
                ArchiveRecord("root/Example.lua", "-- safe\n"),
                ArchiveRecord("root/link.lua", "Example.lua", UnixStat.LINK_FLAG or 0x1ff),
            ), "Example")
        }
    }

    @Test fun `safe addon files are accepted when zip metadata marks them executable`() {
        val archive = archive(
            ArchiveRecord("root/Example.toc", "## Interface: 11200\nExample.lua\n", UnixStat.FILE_FLAG or 0x1ed),
            ArchiveRecord("root/Example.lua", "-- safe addon data\n", UnixStat.FILE_FLAG or 0x1ed),
        )

        val result = AddonArchiveValidator().validate(archive, "Example")

        assertEquals(listOf("Example"), result.addonFolders)
        assertEquals(
            setOf("Example/Example.toc", "Example/Example.lua"),
            result.entries.map { it.relativeName }.toSet(),
        )
        val extracted = Files.createTempDirectory("addon-mode-test-").toFile().apply { deleteOnExit() }
        AddonArchiveExtractor().extract(archive, result, extracted)
        assertEquals("-- safe addon data\n", File(extracted, "Example/Example.lua").readText())
    }

    @Test fun `multi client repository selects only the Vanilla root toc`() {
        val executableFile = UnixStat.FILE_FLAG or 0x1ed
        val archive = archive(
            ArchiveRecord("root/pfQuest.toc", "## Interface: 11200\ndatabase.lua\n"),
            ArchiveRecord("root/pfQuest-tbc.toc", "## Interface: 20400\ndatabase.lua\n"),
            ArchiveRecord("root/pfQuest-wotlk.toc", "## Interface: 30300\ndatabase.lua\n"),
            ArchiveRecord("root/database.lua", "-- shared database\n"),
            ArchiveRecord("root/toolbox/compressdb.sh", "#!/bin/sh\n", executableFile),
        )

        val result = AddonArchiveValidator().validate(archive, "pfQuest")

        assertEquals(listOf("pfQuest"), result.addonFolders)
        assertEquals(
            setOf(
                "pfQuest/pfQuest.toc",
                "pfQuest/pfQuest-tbc.toc",
                "pfQuest/pfQuest-wotlk.toc",
                "pfQuest/database.lua",
                "pfQuest/toolbox/compressdb.sh",
            ),
            result.entries.map { it.relativeName }.toSet(),
        )
    }

    @Test fun `repository root toc accepts Vanilla windows path declarations`() {
        val archive = zip(
            "root/VanillaGuide.toc" to "## Interface: 11200\nlibs\\AceLibrary\\AceLibrary.lua\nCore.lua\n",
            "root/libs/AceLibrary/AceLibrary.lua" to "-- library\n",
            "root/Core.lua" to "-- guide\n",
        )

        val result = AddonArchiveValidator().validate(archive, "VanillaGuide")

        assertEquals(listOf("VanillaGuide"), result.addonFolders)
    }

    @Test fun `validation cooperatively cancels during central directory walk`() {
        val records = buildList {
            add(ArchiveRecord("root/Example.toc", "## Interface: 11200\nExample.lua\n"))
            add(ArchiveRecord("root/Example.lua", "-- safe\n"))
            repeat(32) { add(ArchiveRecord("root/File$it.lua", "-- $it\n")) }
        }
        val archive = archive(*records.toTypedArray())
        var checkpoints = 0
        assertThrows(CancellationException::class.java) {
            AddonArchiveValidator().validate(archive, "Example") {
                if (++checkpoints == 5) throw CancellationException("test cancellation")
            }
        }
    }

    @Test fun `entry count is rejected before materializing beyond the limit`() {
        val file = File.createTempFile("addon-count-test-", ".zip").apply { deleteOnExit() }
        ZipArchiveOutputStream(file).use { output ->
            repeat(20_001) { index ->
                output.putArchiveEntry(ZipArchiveEntry("wrapper/empty-$index.lua"))
                output.closeArchiveEntry()
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            AddonArchiveValidator().validate(file, "TooMany")
        }
    }

    @Test fun `nested multi addon bundle is accepted`() {
        val archive = zip(
            "bundle-root/Core/Core.toc" to "## Interface: 11200\nCore.lua\n",
            "bundle-root/Core/Core.lua" to "-- core\n",
            "bundle-root/Extra/Extra.toc" to "## Interface: 11200\nExtra.xml\n",
            "bundle-root/Extra/Extra.xml" to "<Ui/>\n",
        )
        val result = AddonArchiveValidator().validate(archive, "Bundle")
        assertEquals(setOf("Core", "Extra"), result.addonFolders.toSet())
    }

    @Test fun `VoiceOver data policy accepts only the version agnostic upstream data module`() {
        val executableFile = UnixStat.FILE_FLAG or 0x1ed
        val archive = archive(
            ArchiveRecord("AI_VoiceOverData_Vanilla/AI_VoiceOverData_Vanilla.toc", """
                ## Interface: 100000
                ## OptionalDeps: AI_VoiceOver_112, AI_VoiceOver
                ## X-VoiceOver-DataModule-Version: 1
                Module.lua
                generated/quest_id_lookups.lua
                generated/sounds/quests/example.mp3
            """.trimIndent(), executableFile),
            ArchiveRecord("AI_VoiceOverData_Vanilla/Module.lua", "-- module\n", executableFile),
            ArchiveRecord("AI_VoiceOverData_Vanilla/generated/quest_id_lookups.lua", "return {}\n", executableFile),
            ArchiveRecord("AI_VoiceOverData_Vanilla/generated/sounds/quests/example.mp3", "audio", executableFile),
        )

        assertThrows(IllegalArgumentException::class.java) {
            AddonArchiveValidator().validate(archive, "VoiceOver data")
        }
        val result = AddonArchiveValidator().validate(
            archive,
            "VoiceOver data",
            policy = AddonArchiveValidator.Policy.VOICEOVER_DATA,
        )
        assertEquals(listOf("AI_VoiceOverData_Vanilla"), result.addonFolders)
        assertEquals(4, result.entries.size)
    }

    @Test fun `VoiceOver player policy selects the Vanilla toc from the multi-client release`() {
        val executableFile = UnixStat.FILE_FLAG or 0x1ed
        val archive = archive(
            ArchiveRecord("AI_VoiceOver/AI_VoiceOver.toc", "## Interface: 11200\naddon.xml\n", executableFile),
            ArchiveRecord("AI_VoiceOver/AI_VoiceOver_Mainline.toc", "## Interface: 11509\naddon.xml\n", executableFile),
            ArchiveRecord("AI_VoiceOver/AI_VoiceOver_3.3.5.toc", "## Interface: 30300\naddon.xml\n", executableFile),
            ArchiveRecord("AI_VoiceOver/addon.xml", "<Ui/>\n", executableFile),
        )

        assertEquals(
            listOf("AI_VoiceOver"),
            AddonArchiveValidator().validate(archive, "VoiceOver player").addonFolders,
        )
        val result = AddonArchiveValidator().validate(
            archive,
            "VoiceOver player",
            policy = AddonArchiveValidator.Policy.VOICEOVER_PLAYER,
        )
        assertEquals(listOf("AI_VoiceOver"), result.addonFolders)
        assertEquals(4, result.entries.size)
    }

    @Test fun `VoiceOver data policy rejects lookalike folders and missing dependency metadata`() {
        val wrongFolder = zip(
            "Lookalike/Lookalike.toc" to """
                ## Interface: 100000
                ## OptionalDeps: AI_VoiceOver_112, AI_VoiceOver
                ## X-VoiceOver-DataModule-Version: 1
                Module.lua
            """.trimIndent(),
            "Lookalike/Module.lua" to "-- no\n",
        )
        assertThrows(IllegalArgumentException::class.java) {
            AddonArchiveValidator().validate(
                wrongFolder,
                "VoiceOver data",
                policy = AddonArchiveValidator.Policy.VOICEOVER_DATA,
            )
        }

        val missingDependency = zip(
            "AI_VoiceOverData_Vanilla/AI_VoiceOverData_Vanilla.toc" to """
                ## Interface: 100000
                ## X-VoiceOver-DataModule-Version: 1
                Module.lua
            """.trimIndent(),
            "AI_VoiceOverData_Vanilla/Module.lua" to "-- no\n",
        )
        assertThrows(IllegalArgumentException::class.java) {
            AddonArchiveValidator().validate(
                missingDependency,
                "VoiceOver data",
                policy = AddonArchiveValidator.Policy.VOICEOVER_DATA,
            )
        }
    }

    @Test fun `unsafe and incompatible archives fail closed`() {
        assertThrows(IllegalArgumentException::class.java) {
            AddonArchiveValidator().validate(zip(
                "root/Test.toc" to "## Interface: 11200\n../escape.lua\n",
                "root/escape.lua" to "x",
            ), "Test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AddonArchiveValidator().validate(zip(
                "root/Test.toc" to "## Interface: 30300\nTest.lua\n",
                "root/Test.lua" to "x",
            ), "Test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AddonArchiveValidator().validate(zip(
                "root/Test.toc" to "## Interface: 11200\nMissing.lua\n",
            ), "Test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AddonArchiveValidator().validate(zip(
                "root/Test.toc" to "## Interface: 11200\nTest.lua\n",
                "root/Test.lua" to "x",
                "root/test.LUA" to "y",
            ), "Test")
        }
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        return archive(*entries.map { ArchiveRecord(it.first, it.second) }.toTypedArray())
    }

    private fun archive(vararg entries: ArchiveRecord): File {
        val file = File.createTempFile("addon-test-", ".zip").apply { deleteOnExit() }
        ZipArchiveOutputStream(file).use { output ->
            entries.forEach { record ->
                val entry = ZipArchiveEntry(record.name)
                if (record.unixMode != 0) entry.unixMode = record.unixMode
                output.putArchiveEntry(entry)
                output.write(record.value.toByteArray())
                output.closeArchiveEntry()
            }
        }
        return file
    }

    private data class ArchiveRecord(
        val name: String,
        val value: String,
        val unixMode: Int = 0,
    )
}
