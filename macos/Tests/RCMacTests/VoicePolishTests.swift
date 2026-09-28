import Foundation
import RCCore
import Testing
@testable import RCMac

/// `web/tests/polish.test.ts` — A29, the pure half of dictation polish: the
/// conversation the model is given, and the replacement that touches the
/// dictated words and nothing else.
@Suite("Dictation polish")
struct VoicePolishTests {
    private func timeline(_ bodies: [(String, SessionEventBody)]) -> Timeline {
        var timeline = Timeline()
        for (index, (kind, body)) in bodies.enumerated() {
            _ = timeline.apply(SessionEvent(seq: index + 1, ts: Int64(index + 1), kind: kind,
                                            blockID: "block-\(index + 1)", body: body))
        }
        return timeline
    }

    private func said(_ text: String) -> (String, SessionEventBody) {
        (SessionEvent.userMessageKind, .userMessage(UserMessagePayload(text: text)))
    }

    private func answered(_ text: String) -> (String, SessionEventBody) {
        (SessionEvent.assistantTextKind, .assistantText(StreamTextPayload(text: text, done: true)))
    }

    private func thought(_ text: String) -> (String, SessionEventBody) {
        (SessionEvent.thinkingKind, .thinking(StreamTextPayload(text: text, done: true)))
    }

    @Test func theUserAndAssistantMessagesGoOldestFirstAndNothingElse() {
        let context = DictationPolish.context(timeline([
            said("Make the session status dot pulse while a turn is running."),
            thought("The dot is drawn by StatusDot."),
            answered("Done: the dot now breathes while the state is running.")
        ]))
        #expect(context == [
            PolishContextItem(role: .user, text: "Make the session status dot pulse while a turn is running."),
            PolishContextItem(role: .assistant, text: "Done: the dot now breathes while the state is running.")
        ])
    }

    @Test func theLastTwentyMessagesAreTheOnesNearestTheDictation() {
        let context = DictationPolish.context(timeline((0..<15).flatMap { [said("ask \($0)"), answered("answer \($0)")] }))
        #expect(context.count == DictationPolish.contextLimit)
        #expect(context.first == PolishContextItem(role: .user, text: "ask 5"))
        #expect(context.last == PolishContextItem(role: .assistant, text: "answer 14"))
    }

    @Test func aLongMessageIsTrimmedAndAnEmptyOneDropped() {
        let context = DictationPolish.context(timeline([
            said(String(repeating: "x", count: DictationPolish.contextTextLimit + 500)), answered("   ")
        ]))
        #expect(context.count == 1)
        #expect(context.first?.text.count == DictationPolish.contextTextLimit)
    }

    @Test func aMessageStillOnItsWayCounts() {
        var timeline = timeline([answered("Which test?")])
        timeline.addOptimistic(OptimisticMessage(id: "req-1", text: "The auth refresh one."))
        #expect(DictationPolish.context(timeline) == [
            PolishContextItem(role: .assistant, text: "Which test?"),
            PolishContextItem(role: .user, text: "The auth refresh one.")
        ])
    }

    @Test func theRequestCarriesTheDictatedSpanTheChoicesAndAuto() {
        let request = DictationPolishing.request(
            span: PolishSpan(base: "note: ", dictated: "um the green blinking thing"),
            model: "gpt-4.1-mini", strength: .strong,
            context: [PolishContextItem(role: .user, text: "Make the dot pulse.")])
        #expect(request == PolishRequest(text: "um the green blinking thing", model: "gpt-4.1-mini",
                                         strength: .strong, language: "auto",
                                         context: [PolishContextItem(role: .user, text: "Make the dot pulse.")]))
    }

    @Test func anEmptyOrOverlongDictationIsNotPolished() {
        #expect(!DictationPolish.canPolish("   "))
        #expect(DictationPolish.canPolish(String(repeating: "a", count: 8192)))
        #expect(!DictationPolish.canPolish(String(repeating: "a", count: 8193)))
    }

    private let span = PolishSpan(base: "after lunch", dictated: "um run the the auth suite")

    @Test func theAnswerReplacesTheDictatedSpanAndLeavesWhatWasTyped() {
        #expect(span.dictatedDraft == "after lunch um run the the auth suite")
        let next = DictationPolishing.applyPolished(current: span.dictatedDraft, span: span,
                                                    polished: "Run the auth suite.")
        #expect(next == "after lunch Run the auth suite.")
        #expect(next == span.polishedDraft("Run the auth suite."))
    }

    @Test func whitespaceIsTrimmedAndAnEmptyAnswerIsNoAnswer() {
        #expect(DictationPolishing.applyPolished(current: span.dictatedDraft, span: span,
                                                 polished: "  Run the auth suite.\n") == "after lunch Run the auth suite.")
        #expect(DictationPolishing.applyPolished(current: span.dictatedDraft, span: span, polished: "   ") == nil)
    }

    @Test func aFieldThePersonHasMovedOnIsLeftAlone() {
        #expect(DictationPolishing.applyPolished(current: "something else entirely", span: span,
                                                 polished: "Run the auth suite.") == nil)
        #expect(DictationPolishing.applyPolished(current: span.dictatedDraft, span: span,
                                                 polished: span.dictated) == nil)
    }

    @Test func undoPutsTheDictatedWordsBackOnlyFromThePolishedDraft() {
        let polished = span.polishedDraft("Run the auth suite.")
        #expect(DictationPolishing.undoPolished(current: polished, span: span,
                                                polished: "Run the auth suite.") == span.dictatedDraft)
        #expect(DictationPolishing.undoPolished(current: "edited since", span: span,
                                                polished: "Run the auth suite.") == nil)
    }
}
