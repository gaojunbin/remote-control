import Testing
@testable import RCMac

extension LanguageSensitive {
    /// `web/tests/format.test.ts`, case for case, in English.
    @Suite("Format") @MainActor
    struct FormatTests {
        private let now: Int64 = 1_700_000_000_000
        private func ago(_ ms: Int64) -> Int64 { now - ms }

        init() { InterfaceLanguageSource.shared.current = .en }

        @Test func compactScaleOfTheSessionRows() {
            #expect(Format.relativeTime(ago(1_000), now: now) == "now")
            #expect(Format.relativeTime(ago(4 * 60_000), now: now) == "4m")
            #expect(Format.relativeTime(ago(3 * 3_600_000), now: now) == "3h")
            #expect(Format.relativeTime(ago(2 * 86_400_000), now: now) == "2d")
        }

        @Test func longerScaleOfRecentDirectories() {
            #expect(Format.relativeAgo(ago(30_000), now: now) == "just now")
            #expect(Format.relativeAgo(ago(2 * 3_600_000), now: now) == "2h ago")
            #expect(Format.relativeAgo(ago(30 * 3_600_000), now: now) == "yesterday")
        }

        /// `toLocaleDateString(dateLocale, { month: 'short', day: 'numeric' })`,
        /// in the local time zone as a browser writes it.
        @Test func aDateOlderThanAWeekIsWrittenAsADate() throws {
            let old = Format.relativeTime(ago(30 * 86_400_000), now: now)
            #expect(try Regex("^(Oct|Nov) [0-9]{1,2}$").wholeMatch(in: old) != nil)
            InterfaceLanguageSource.shared.current = .zhHans
            let chinese = Format.relativeTime(ago(30 * 86_400_000), now: now)
            #expect(try Regex("^1[01]月[0-9]{1,2}日$").wholeMatch(in: chinese) != nil)
            #expect(Format.relativeAgo(ago(3 * 60_000), now: now) == "3 分钟前")
            InterfaceLanguageSource.shared.current = .en
        }

        @Test func toolDurations() {
            #expect(Format.duration(820) == "820ms")
            #expect(Format.duration(6_400) == "6.4s")
            #expect(Format.duration(38_020) == "38s")
            #expect(Format.duration(72_000) == "1m 12s")
            #expect(Format.duration(3 * 3_600_000 + 5 * 60_000) == "3h 5m")
        }

        @Test func countdownClocks() {
            #expect(Format.clock(12_000) == "0:12")
            #expect(Format.clock(587_000) == "9:47")
            #expect(Format.clock(-5) == "0:00")
        }

        @Test func tokenCountsAndSizes() {
            #expect(Format.compactNumber(940) == "940")
            #expect(Format.compactNumber(48_200) == "48.2k")
            #expect(Format.compactNumber(203_000) == "203k")
            #expect(Format.compactNumber(1_500_000) == "1.5M")
            #expect(Format.bytes(512) == "512 B")
            #expect(Format.bytes(6 * 1024 * 1024) == "6.0 MiB")
        }

        @Test func pathsAndLatency() {
            #expect(Format.tildePath("/Users/me/dev/gateway") == "~/dev/gateway")
            #expect(Format.tildePath("/home/ci/work/api") == "~/work/api")
            #expect(Format.tildePath("/opt/tools") == "/opt/tools")
            #expect(Format.tildePath("/Users/me/dev", home: "/Users/me") == "~/dev")
            #expect(Format.baseName("/Users/me/dev/gateway") == "gateway")
            #expect(Format.baseName("/Users/me/dev/gateway/") == "gateway")
            #expect(Format.baseName("/") == "/")
            #expect(Format.latency(18) == "18 ms")
            #expect(Format.latency(nil) == "—")
        }

        @Test func foldLines() {
            let short = Format.foldLines("a\nb\nc", maxLines: 20)
            #expect(!short.folded && short.head == "a\nb\nc" && short.total == 3)
            let text = (0..<50).map { "line \($0)" }.joined(separator: "\n")
            let long = Format.foldLines(text, maxLines: 20)
            #expect(long.folded && long.total == 50)
            #expect(long.head.split(separator: "\n").count == 20)
        }

        @Test func javaScriptRounding() {
            #expect(Format.toFixed(0.25, 1) == "0.3")
            #expect(Format.jsRound(2.5) == 3)
            #expect(Format.jsRound(-2.5) == -2)
        }
    }
}
