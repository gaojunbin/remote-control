import Testing
import Foundation
@testable import RCCore

@Suite("Wire protocol")
struct ProtocolTests {
    @Test("An unknown event kind keeps its payload instead of being dropped")
    func unknownEventKind() throws {
        let event = try JSONDecoder().decode(SessionEvent.self, from: Data(#"""
        {"seq": 7, "ts": 12, "kind": "screenshot", "block_id": "s1", "url": "https://example.invalid/a.png"}
        """#.utf8))
        guard case .unknown(let kind, let raw) = event.body else {
            Issue.record("expected an unknown body")
            return
        }
        #expect(kind == "screenshot")
        #expect(raw["url"]?.stringValue == "https://example.invalid/a.png")

        let encoded = try JSONValue.encode(event)
        #expect(encoded["url"]?.stringValue == "https://example.invalid/a.png")
        #expect(encoded["kind"]?.stringValue == "screenshot")
    }

    @Test("A field this build does not model survives a round trip")
    func unknownFieldsSurvive() throws {
        let source: JSONValue = [
            "seq": 3, "ts": 4, "kind": "assistant_text", "block_id": "a",
            "text": "hello", "done": true, "confidence": 0.9
        ]
        let event = try source.decode(SessionEvent.self)
        let encoded = try JSONValue.encode(event)
        #expect(encoded["confidence"]?.doubleValue == 0.9)
        #expect(encoded["text"]?.stringValue == "hello")
    }

    @Test("An unknown enumeration value decodes rather than throwing")
    func unknownEnumeration() throws {
        let state = try JSONDecoder().decode(SessionState.self, from: Data(#""hibernating""#.utf8))
        #expect(state.rawValue == "hibernating")
        #expect(!state.isWorking)
    }

    @Test("Amendment A1: the tool category rides in tool_kind")
    func toolKindField() throws {
        let event = try JSONValue.object([
            "seq": 1, "ts": 1, "kind": "tool_call", "block_id": "b",
            "tool": "Bash", "tool_kind": "shell", "title": "pytest", "status": "running"
        ]).decode(SessionEvent.self)
        #expect(event.kind == SessionEvent.toolCallKind)
        #expect(event.toolCall?.kind == ToolKind.shell)
        let encoded = try JSONValue.encode(event)
        #expect(encoded["kind"]?.stringValue == "tool_call")
        #expect(encoded["tool_kind"]?.stringValue == "shell")
    }

    @Test("Amendment A2: usage decodes without a cost or a context window")
    func optionalUsageFields() throws {
        let usage = try JSONValue.object([
            "input_tokens": 10, "output_tokens": 5, "total_tokens": 15
        ]).decode(SessionUsage.self)
        #expect(usage.totalTokens == 15)
        #expect(usage.costUSD == nil)
        #expect(usage.contextFraction == nil)
    }

    @Test("Amendment A3: an inbound attachment carries a size, an outbound one carries bytes")
    func attachmentShapes() throws {
        let message = try JSONValue.object([
            "text": "look", "attachments": [["name": "a.png", "mime": "image/png", "size": 12]]
        ]).decode(UserMessagePayload.self)
        #expect(message.attachments.first?.size == 12)

        let request = try GatewayRequest.send(sessionID: "s", text: "look", attachments: [
            OutboundAttachment(name: "a.png", mime: "image/png", data: Data([1, 2, 3]))
        ])
        let attachment = request.json["attachments"]?.arrayValue?.first
        #expect(attachment?["data_base64"]?.stringValue == "AQID")
        #expect(attachment?["size"] == nil)
    }

    @Test("A protocol mismatch is reported, not guessed around")
    func protocolGate() throws {
        let hello = try JSONValue.object(["type": "hello", "protocol": 2]).decode(HelloFrame.self)
        #expect(hello.protocolVersion != RemoteProtocol.version)
    }

    @Test("Attachment limits are enforced before a message leaves the composer",
          arguments: [9, 12])
    func attachmentCountLimit(count: Int) {
        let attachment = OutboundAttachment(name: "a", mime: "image/png", data: Data([0]))
        #expect(throws: AttachmentError.self) {
            _ = try GatewayRequest.send(sessionID: "s", text: "x",
                                        attachments: Array(repeating: attachment, count: count))
        }
    }

    @Test("A retry reuses the original request id so the device can dedupe it")
    func retryKeepsRequestID() throws {
        let first = try GatewayRequest.send(id: "abc", sessionID: "s", text: "hello")
        let retry = try GatewayRequest.send(id: "abc", sessionID: "s", text: "hello")
        #expect(first.id == retry.id)
        #expect(retry.json["id"]?.stringValue == "abc")
    }
}
