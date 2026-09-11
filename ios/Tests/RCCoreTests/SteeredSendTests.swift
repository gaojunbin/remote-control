import Testing
import Foundation
@testable import RCCore

/// Amendment A14: a message sent into a running turn is read by the agent at
/// its next step, so the device emits the `user_message` when the agent takes
/// it and not when it was sent. Until that block arrives the app's own row is
/// the message, and it belongs at the foot of the transcript: that is where the
/// device's block will land.
@Suite("Steered send order")
struct SteeredSendTests {
    private let requestID = "req-steer"

    private func userMessage(_ text: String, id: String, seq: Int, firstSeq: Int,
                             source: EventSource = .remote) -> SessionEvent {
        SessionEvent(seq: seq, ts: 0, kind: SessionEvent.userMessageKind, blockID: id,
                     firstSeq: firstSeq,
                     body: .userMessage(UserMessagePayload(text: text, source: source)))
    }

    private func assistantText(_ delta: String, id: String, seq: Int, firstSeq: Int,
                               done: Bool = false) -> SessionEvent {
        SessionEvent(seq: seq, ts: 0, kind: SessionEvent.assistantTextKind, blockID: id,
                     firstSeq: firstSeq,
                     body: .assistantText(StreamTextPayload(delta: delta, done: done)))
    }

    private func toolCall(_ title: String, id: String, seq: Int, firstSeq: Int,
                          status: ToolStatus) -> SessionEvent {
        SessionEvent(seq: seq, ts: 0, kind: SessionEvent.toolCallKind, blockID: id,
                     firstSeq: firstSeq,
                     body: .toolCall(ToolCallPayload(tool: "Bash", kind: .shell, title: title,
                                                     status: status)))
    }

    private func text(of entry: TimelineEntry) -> String {
        if let message = entry.userMessage { return message.text }
        if let tool = entry.toolCall { return tool.title }
        return entry.text
    }

    /// The session that produced amendment A14, replayed: the app steers a
    /// running Codex turn, the turn keeps talking, and the device's own block
    /// for the steered message arrives after the output that preceded it. The
    /// transcript must end in the order the terminal on the same session drew.
    @Test("The row waits at the foot of a running turn and lands where the device puts it")
    @MainActor
    func steeredRowHoldsTheFootUntilTheDeviceBlockArrives() {
        var timeline = Timeline()
        timeline.apply(userMessage("当前工作目录是哪里", id: "u-42", seq: 42, firstSeq: 42))

        // The send: `accepted: "steered"` leaves this row standing (A12 + A14).
        timeline.addOptimistic(OptimisticMessage(id: requestID, text: "有什么项目"))
        timeline.markSteered(requestID)
        #expect(timeline.roots.last?.pending?.id == requestID)

        // The turn was mid-step when the message was sent and carries on: text
        // it had already started, the tool it had already called, then the
        // answer to the *previous* message. All of it sorts above the row.
        timeline.apply(assistantText("我确认一下", id: "a-46", seq: 46, firstSeq: 46))
        timeline.apply(assistantText("当前工作目录。", id: "a-46", seq: 49, firstSeq: 46, done: true))
        #expect(timeline.roots.last?.pending?.id == requestID, "streaming text does not displace it")

        timeline.apply(toolCall("pwd", id: "t-50", seq: 50, firstSeq: 50, status: .running))
        timeline.apply(toolCall("pwd", id: "t-50", seq: 51, firstSeq: 50, status: .succeeded))
        #expect(timeline.roots.last?.pending?.id == requestID, "nor does a tool call or its result")

        timeline.apply(assistantText("当前工作目录是 /Users/junbingao。", id: "a-53", seq: 58,
                                     firstSeq: 53, done: true))
        #expect(timeline.roots.last?.pending?.id == requestID,
                "the agent has not taken the message yet, so the row is still the message")
        #expect(timeline.optimistic.count == 1)

        // Codex reads the steered message at its next step and the device emits
        // the block then, under the request id and after the output above it.
        timeline.apply(userMessage("有什么项目", id: requestID, seq: 59, firstSeq: 59))
        #expect(timeline.optimistic.isEmpty, "the device's block is the message now")

        timeline.apply(assistantText("我查看一下当前目录…", id: "a-60", seq: 65, firstSeq: 60, done: true))

        #expect(timeline.roots.map(\.id) == ["u-42", "a-46", "t-50", "a-53", requestID, "a-60"])
        #expect(timeline.roots.map(text(of:)) == [
            "当前工作目录是哪里",
            "我确认一下当前工作目录。",
            "pwd",
            "当前工作目录是 /Users/junbingao。",
            "有什么项目",
            "我查看一下当前目录…"
        ], "the same order the terminal on this session drew")
        #expect(timeline.roots.filter { $0.userMessage?.text == "有什么项目" }.count == 1,
                "and exactly one copy of the steered message")
    }

    @Test("The end of the turn does not retire a steered row")
    @MainActor
    func turnCompletedLeavesTheRowStanding() {
        var timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id: requestID, text: "有什么项目"))
        timeline.markSteered(requestID)

        timeline.apply(SessionEvent(seq: 70, ts: 0, kind: SessionEvent.turnCompletedKind,
                                    body: .turnCompleted(TurnCompletedPayload(
                                        turnID: "t", stopReason: .completed, durationMS: 900))))
        #expect(timeline.optimistic.count == 1,
                "the device emits the block at the turn's end; only that block retires the row")

        timeline.apply(userMessage("有什么项目", id: requestID, seq: 71, firstSeq: 71))
        #expect(timeline.optimistic.isEmpty)
    }

    @Test("A steered row waits without ever claiming the send is lost")
    func steeringIsNotAnUnconfirmedDelivery() {
        let sent = Date().addingTimeInterval(-600)
        let waiting = OptimisticMessage(id: "req", text: "有什么项目", sentAt: sent, isSteering: true)
        #expect(!waiting.isUnconfirmed(), "the device took it; the agent has not read it yet")

        var timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id: "req", text: "有什么项目", sentAt: sent))
        #expect(timeline.unconfirmedOptimistic().count == 1, "before the reply it is simply in flight")
        timeline.markSteered("req")
        #expect(timeline.unconfirmedOptimistic().isEmpty)
        #expect(timeline.optimistic.count == 1, "and it is still on screen either way")
    }

    /// A page of older history can hold a message whose words this send repeats
    /// ("continue", "go on"). Under A14 the row waits for a whole turn, so that
    /// page must not be allowed to retire it: history is older than the send.
    @Test("An older copy of the same words in history does not retire the row")
    @MainActor
    func historyDoesNotReconcileByText() {
        var timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id: requestID, text: "继续"))
        timeline.markSteered(requestID)

        timeline.prependHistory([userMessage("继续", id: "older-block", seq: 8, firstSeq: 8)],
                                hasMore: false)
        #expect(timeline.optimistic.count == 1, "an older message is not this one")

        timeline.prependHistory([userMessage("继续", id: requestID, seq: 30, firstSeq: 30)],
                                hasMore: false)
        #expect(timeline.optimistic.isEmpty, "the block under the request id still is")
    }

    /// A device that mints its own ids is still reconciled by text, live.
    @Test("The older-device fallback still retires the row from the live stream")
    @MainActor
    func liveTextFallbackStillApplies() {
        var timeline = Timeline()
        timeline.addOptimistic(OptimisticMessage(id: requestID, text: "有什么项目"))
        timeline.markSteered(requestID)

        timeline.apply(userMessage("有什么项目", id: "device-block", seq: 59, firstSeq: 59))
        #expect(timeline.optimistic.isEmpty)
        #expect(timeline.roots.map(\.id) == ["device-block"])
    }
}
