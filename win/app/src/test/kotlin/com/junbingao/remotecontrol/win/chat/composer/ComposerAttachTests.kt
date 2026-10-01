package com.junbingao.remotecontrol.win.chat.composer

import com.junbingao.remotecontrol.core.protocol.SessionControl
import com.junbingao.remotecontrol.win.shared.AttachmentLimits
import com.junbingao.remotecontrol.win.shared.Format
import com.junbingao.remotecontrol.win.strings.S
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `web/tests/attachment-cap.test.tsx` and `session-drafts.test.tsx`: the files of a draft, their cap,
 * and whose draft they are. The Mac's `ComposerAttachTests`, case for case.
 */
class ComposerAttachTests {
    private fun photos(count: Int, from: Int = 1): List<AttachmentSource> =
        (from until from + count).map { AttachmentSource.Data(name = "photo-$it.jpg", mime = "image/jpeg", data = ByteArray(10)) }

    @Test
    fun twoBatchesAtOnceNeverPassTheCapAndSaySoOnce() = runTest {
        val harness = ComposerHarness(this)
        harness.composer.attach(photos(6))
        harness.composer.attach(photos(6, from = 7))
        assertTrue(eventually { harness.composer.attachments.size == AttachmentLimits.maxAttachments })
        assertTrue(eventually { harness.composer.errors.isNotEmpty() })
        assertEquals(listOf(S.composer.attachTooMany(AttachmentLimits.maxAttachments)), harness.composer.errors)
    }

    @Test
    fun whatTheSecondBatchSaysIsAddedToWhatTheFirstSaid() = runTest {
        val harness = ComposerHarness(this)
        val big = ByteArray(AttachmentLimits.maxAttachmentBytes + 1)
        harness.composer.attach(listOf(AttachmentSource.Data(name = "huge.bin", mime = "application/octet-stream", data = big)))
        assertTrue(eventually { harness.composer.errors.isNotEmpty() })
        harness.composer.attach(photos(9))
        assertTrue(eventually { harness.composer.errors.size == 2 })
        assertEquals(S.composer.attachTooLarge("huge.bin", Format.bytes(AttachmentLimits.maxAttachmentBytes)), harness.composer.errors.first())
        assertEquals(S.composer.attachTooMany(AttachmentLimits.maxAttachments), harness.composer.errors.last())
    }

    @Test
    fun aFileIsReadWithTheTypeItsNameSays(@TempDir directory: Path) {
        val folder = directory.toFile()
        val file = File(folder, "notes.txt").apply { writeText("hello notes") }
        val read = AttachmentRead.read(listOf(AttachmentSource.File(file), AttachmentSource.File(File(folder, "missing.png"))), alreadyAttached = 0)
        assertEquals(listOf("notes.txt"), read.attachments.map { it.name })
        assertEquals("text/plain", read.attachments.first().mime)
        assertEquals(11, read.attachments.first().size)
        assertEquals(listOf(S.composer.attachFailed("missing.png")), read.errors.toList())
    }

    @Test
    fun nothingIsTakenWhereTheAttachmentButtonIsNotDrawn() = runTest {
        val harness = ComposerHarness(this, session = ComposerFixture.session(control = SessionControl.shared))
        assertFalse(harness.composer.acceptsFiles)
        harness.composer.attach(photos(1))
        pass(50)
        assertTrue(harness.composer.attachments.isEmpty())
    }

    @Test
    fun removingAChipTakesThatFileOff() = runTest {
        val harness = ComposerHarness(this)
        val files = (1..3).map { ComposerAttachment(name = "f$it", mime = "text/plain", data = ByteArray(0)) }
        harness.host.drafts.add(files, to = harness.composer.key)
        harness.composer.removeAttachment(at = 1)
        assertEquals(listOf("f1", "f3"), harness.composer.attachments.map { it.name })
    }
}

/** The files of every session's draft, kept apart by session. */
class ComposerDraftsTests {
    private val file = ComposerAttachment(name = "a", mime = "text/plain", data = ByteArray(0))

    @Test
    fun eachSessionKeepsItsOwnFiles() {
        val drafts = ComposerDrafts()
        drafts.add(listOf(file), to = "dev/a")
        assertTrue(drafts.attachments("dev/b").isEmpty())
        assertEquals(listOf(file), drafts.attachments("dev/a"))
    }

    @Test
    fun theCapIsKeptWhereTheListIsWritten() {
        val drafts = ComposerDrafts()
        val many = (0 until 10).map { ComposerAttachment(name = "$it", mime = "text/plain", data = ByteArray(0)) }
        assertEquals(2, drafts.add(many, to = "k"))
        assertEquals(AttachmentLimits.maxAttachments, drafts.attachments("k").size)
        assertEquals(0, drafts.add(emptyList(), to = "k"))
    }

    @Test
    fun aRefusalHandsFilesBackOnlyToAnEmptyDraft() {
        val drafts = ComposerDrafts()
        val newer = ComposerAttachment(name = "newer", mime = "text/plain", data = ByteArray(0))
        drafts.restore(listOf(file), to = "k")
        assertEquals(listOf(file), drafts.attachments("k"))
        drafts.clear("k")
        drafts.add(listOf(newer), to = "k")
        drafts.restore(listOf(file), to = "k")
        assertEquals(listOf(newer), drafts.attachments("k"))
    }

    /** A43: while a queued message is edited the files wait aside. */
    @Test
    fun anEditSetsTheFilesAsideAndGivesThemBack() {
        val drafts = ComposerDrafts()
        drafts.add(listOf(file), to = "k")
        drafts.setAside("k")
        assertTrue(drafts.attachments("k").isEmpty())
        drafts.add(listOf(ComposerAttachment(name = "during", mime = "text/plain", data = ByteArray(0))), to = "k")
        drafts.endEdit("k")
        assertEquals(listOf(file), drafts.attachments("k"))
    }

    @Test
    fun signingOutEmptiesEverything() {
        val drafts = ComposerDrafts()
        drafts.add(listOf(file), to = "k")
        drafts.setAside("k")
        drafts.reset()
        drafts.endEdit("k")
        assertTrue(drafts.attachments("k").isEmpty())
    }
}
