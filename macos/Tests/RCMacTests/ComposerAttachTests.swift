import Foundation
import RCCore
import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/attachment-cap.test.tsx` and `session-drafts.test.tsx`: the
    /// files of a draft, their cap, and whose draft they are.
    @Suite("Composer attachments", .serialized) @MainActor
    struct ComposerAttachTests {
        private func photos(_ count: Int, from start: Int = 1) -> [AttachmentSource] {
            (start..<start + count).map { .data(name: "photo-\($0).jpg", mime: "image/jpeg", Data(count: 10)) }
        }

        @Test func twoBatchesAtOnceNeverPassTheCapAndSaySoOnce() async {
            let harness = ComposerHarness()
            harness.composer.attach(photos(6))
            harness.composer.attach(photos(6, from: 7))
            #expect(await composerEventually { harness.composer.attachments.count == AttachmentLimits.maxAttachments })
            #expect(await composerEventually { !harness.composer.errors.isEmpty })
            #expect(harness.composer.errors == [S.composer.attachTooMany(AttachmentLimits.maxAttachments)])
        }

        @Test func whatTheSecondBatchSaysIsAddedToWhatTheFirstSaid() async {
            let harness = ComposerHarness()
            let big = Data(count: AttachmentLimits.maxAttachmentBytes + 1)
            harness.composer.attach([.data(name: "huge.bin", mime: "application/octet-stream", big)])
            #expect(await composerEventually { !harness.composer.errors.isEmpty })
            harness.composer.attach(photos(9))
            #expect(await composerEventually { harness.composer.errors.count == 2 })
            #expect(harness.composer.errors.first == S.composer.attachTooLarge("huge.bin",
                                                                              Format.bytes(AttachmentLimits.maxAttachmentBytes)))
            #expect(harness.composer.errors.last == S.composer.attachTooMany(AttachmentLimits.maxAttachments))
        }

        @Test func aFileIsReadWithTheTypeItsNameSays() throws {
            let folder = FileManager.default.temporaryDirectory.appending(path: "composer-\(UUID().uuidString)")
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            defer { try? FileManager.default.removeItem(at: folder) }
            let file = folder.appending(path: "notes.txt")
            try Data("hello notes".utf8).write(to: file)
            let read = AttachmentRead.read([.file(file), .file(folder.appending(path: "missing.png"))],
                                           alreadyAttached: 0)
            #expect(read.attachments.map(\.name) == ["notes.txt"])
            #expect(read.attachments.first?.mime == "text/plain")
            #expect(read.attachments.first?.size == 11)
            #expect(read.errors == [S.composer.attachFailed("missing.png")])
        }

        @Test func nothingIsTakenWhereTheAttachmentButtonIsNotDrawn() async throws {
            let harness = ComposerHarness(session: ComposerFixture.session(control: .shared))
            #expect(!harness.composer.acceptsFiles)
            harness.composer.attach(photos(1))
            try await Task.sleep(for: .milliseconds(50))
            #expect(harness.composer.attachments.isEmpty)
        }

        @Test func removingAChipTakesThatFileOff() {
            let harness = ComposerHarness()
            let files = (1...3).map { ComposerAttachment(name: "f\($0)", mime: "text/plain", data: Data()) }
            harness.host.drafts.add(files, to: harness.composer.key)
            harness.composer.removeAttachment(at: 1)
            #expect(harness.composer.attachments.map(\.name) == ["f1", "f3"])
        }
    }
}

/// The files of every session's draft, kept apart by session.
@Suite("Composer drafts") @MainActor
struct ComposerDraftsTests {
    private let file = ComposerAttachment(name: "a", mime: "text/plain", data: Data())

    @Test func eachSessionKeepsItsOwnFiles() {
        let drafts = ComposerDrafts()
        drafts.add([file], to: "dev/a")
        #expect(drafts.attachments("dev/b").isEmpty)
        #expect(drafts.attachments("dev/a") == [file])
    }

    @Test func theCapIsKeptWhereTheListIsWritten() {
        let drafts = ComposerDrafts()
        let many = (0..<10).map { ComposerAttachment(name: "\($0)", mime: "text/plain", data: Data()) }
        #expect(drafts.add(many, to: "k") == 2)
        #expect(drafts.attachments("k").count == AttachmentLimits.maxAttachments)
        #expect(drafts.add([], to: "k") == 0)
    }

    @Test func aRefusalHandsFilesBackOnlyToAnEmptyDraft() {
        let drafts = ComposerDrafts()
        let newer = ComposerAttachment(name: "newer", mime: "text/plain", data: Data())
        drafts.restore([file], to: "k")
        #expect(drafts.attachments("k") == [file])
        drafts.clear("k")
        drafts.add([newer], to: "k")
        drafts.restore([file], to: "k")
        #expect(drafts.attachments("k") == [newer])
    }

    /// A43: while a queued message is edited the files wait aside.
    @Test func anEditSetsTheFilesAsideAndGivesThemBack() {
        let drafts = ComposerDrafts()
        drafts.add([file], to: "k")
        drafts.setAside("k")
        #expect(drafts.attachments("k").isEmpty)
        drafts.add([ComposerAttachment(name: "during", mime: "text/plain", data: Data())], to: "k")
        drafts.endEdit("k")
        #expect(drafts.attachments("k") == [file])
    }

    @Test func signingOutEmptiesEverything() {
        let drafts = ComposerDrafts()
        drafts.add([file], to: "k")
        drafts.setAside("k")
        drafts.reset()
        drafts.endEdit("k")
        #expect(drafts.attachments("k").isEmpty)
    }
}
