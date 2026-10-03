# android/core — the Kotlin core

The Kotlin port of the iPhone app's `ios/Sources/RCCore`, shared by the Android app (`android/app`)
and the Windows app (`win/app`, which includes this directory as its `:core`), exactly as the Mac app
shares RCCore with the iPhone app. Plain Kotlin on the JVM: no Android API, no desktop-only API, no
`java.net.http`, nothing newer than Android API 29 — so both builds load the same jar.

This first half ports RCCore's `Protocol/`, `Transport/`, `Persistence/`, `Markdown/` and the leaves
of `State/` those four use. The stores (`State/` proper) and the demo gateway (`Demo/`) come next, in
`…core.state` and `…core.demo`, and build on the types below, which are named and shaped as RCCore's.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
cd android && ./gradlew --no-daemon :core:test                 # everything but the live check
RC_MOCK_GATEWAY=http://127.0.0.1:8796 ./gradlew --no-daemon :core:test --tests '*Live*'
cd win && ./gradlew --no-daemon :core:compileKotlin             # the Windows build compiles it too
```

The live check needs a gateway at that address, such as the web app's mock
(`cd web && PORT=8796 npx tsx mock/server.ts`, signing in as `admin` / `dev`); without the variable
it is skipped. `RC_MOCK_PASSWORD` overrides the password.

## Package layout

One Swift file became one Kotlin file of the same name, in the matching package
(`com.junbingao.remotecontrol.core.…`); the two that ran long lost a part to a file of its own
(`GatewayEvent.kt` from `GatewaySocket.swift`, `MarkdownParser.kt` from `MarkdownDocument.swift`).

| Package | Files (RCCore source) |
| --- | --- |
| `protocol` | `AgentAccount`, `AgentLabel`, `AppFrame`, `AppsInfo`, `Command`, `Device`, `EventPayloads`, `JSONValue`, `Preferences`, `ReplyResults`, `Requests`, `Resume`, `Session`, `SessionEvent`, `SharedSetting`, `TerminalFrames`, `User`, `WireEnum` (all of `Protocol/`) |
| `transport` | `EventBuffer`, `GatewayEndpoint`, `GatewayHTTPClient`, `GatewayHTTPModels`, `GatewaySocket` (with `GatewayEvent`), `GatewayUserModels`, `PolishModels`, `STTSocket`, `WebSocketConnection` (all of `Transport/`) |
| `persistence` | `DraftStore`, `LocalCache`, `SecretStore` (all of `Persistence/`) |
| `markdown` | `MarkdownDocument` (with `MarkdownParser`) |
| `state` | `InterfaceLanguage` (with `L10n`), `Localizable` (the core's zh-Hans table), `AppVersion` (with `AppBuild`, `InstalledApp`, `AppUpdateRequirement`), `GatewayChannel` (with `GatewayAPI`), `DictationLanguage`, `TimelineDetail` and `VoiceBackend` (the two enums from `SettingsStore.swift`), `RelativeTime` (from `SessionStore.swift`) |
| root | `FoundationText` and `Formatting`, internal: the Foundation behaviour RCCore leans on (below) |

`VoiceBackend.inEffect(settings:connection:)`, the overload that reads two stores, is the stores'
to add, as a companion extension next to them.

## The porting contract as applied

ROUND-56's contract holds throughout. What it did not settle was decided as follows.

**Values.**
- A Swift `struct … : WireEnum` is a `@JvmInline value class X(override val rawValue: String) : WireEnum`
  with the named values in its companion (`SessionState.running`) and a `Serializer` object that
  reads any string: open-ended, as RCCore's are. `toString()` is the raw value.
- Swift `Int` is Kotlin `Int` and `Int64` is `Long`, as the types say. RCCore's `JSONValue.intValue`
  is 64 bits wide; here `intValue` is 32 and **`longValue` reads milliseconds** (the demo gateway's
  `request.body["at"]` is one).
- `TimeInterval` is `kotlin.time.Duration`; a `Date` RCCore does arithmetic on is `java.time.Instant`
  (`RelativeTime`, `ResumeBounds`, `GatewayRequest.resumeSet(at:)`, `UserRecord.lastLoginSummary(now:)`).
- A `URL` is `java.net.URI` (`GatewayEndpoint.url`/`socketURL`/`apiURL`, `SessionLink.url`,
  `PushRoute.deepLink`, `AppUpdateRequirement.updateURL`, `MarkdownLink.filePath(url)`); a directory
  is `java.io.File`.
- Structs' `var`s are `val`s, as the contract says: a store changes a `Session` or `Device` with `copy`.
- An initializer with another shape than the primary constructor is a companion `operator fun invoke`,
  so the call reads as RCCore's: `AppFrame(json = …)`, `AppFrame(data = …)`, `TerminalOutput(json = …)`,
  `GatewayEndpoint("…")`, `SessionLink(url = …)`, `PushRoute(userInfo = …)`, `SocketCloseReason(code = …)`,
  `SpeedChange(id = …)`, and `X(rawValue = …)` returning null on every raw-value enum. `AppVersion("1.2.3")`
  is a secondary constructor. `allCases` is kept beside `entries`.
- An enum case's unnamed payload is named for its role: `payload` on `SessionEventBody`; `hello`,
  `device`, `session`, `progress`, `preferences`, `output`, `exited` on `AppFrame`; `state`, `frame`,
  `reason`, `id`, `error` on `GatewayEvent`; `text`, `message` on `STTEvent`. Where a case shares its
  type's name (`AppFrame.SessionEvent`, `MarkdownBlock.Content.List`), the type is written in full.
- Argument labels: a Swift label is the Kotlin parameter name, unless it is `_` or a Kotlin keyword
  (`for`, `in`, `as`), when the internal name is (`support(app)`, `localeIdentifier(code)`, `spans(source)`).

**Errors.** Each error enum is a sealed class extending `Exception` without a stack trace, as a Swift
error has none, with `errorDescription` and `message` both the localized sentence. `GatewayErrorBody`
is one too: it is what a refused request throws and the failure of `AppFrame.Reply.result`, a
`kotlin.Result<JsonElement>`.

**JSON.** One `WireJson` (`ignoreUnknownKeys`, `explicitNulls = false`, `encodeDefaults`, and
`coerceInputValues`, which is what makes a `null` read as RCCore's `decodeIfPresent … ?? default`).
- `@Required` where RCCore's synthesized decoding requires a field its initializer defaults
  (`CachedWorkspace`, `PolishRequest.context`); a private surrogate where the decoding differs from
  the initializer (`PushRoute.version` is 0 when absent, 1 when built) or RCCore decodes by hand
  (`MetaPayload.speed` — present-and-null is `SpeedChange.Standard`, absent is null —
  `HealthResponse.auth`, `Command`, `GatewayErrorBody`, `PolishModelsResponse`, `SessionEvent`).
- Objects keep their keys in order, so the Mac's alphabetical tool input does not come back.
  `SessionEvent` writes its envelope (`seq`, `ts`, `kind`, `first_seq`, `block_id`,
  `parent_block_id`), then the raw frame's keys in arrival order with the typed values winning, then
  typed keys the frame lacked. `GatewayRequest.json` is `type`, `id`, then the body in order.
- Two differences from Foundation, pinned in `WireJsonTests`: a quoted number or boolean is read as
  its value where RCCore refuses the frame, and `5.0` in a whole-number field is refused where RCCore
  takes it. No gateway or device sends either.
- `JSONValue` is an object holding `encode`, `parse` and `emptyObject`; the tree accessors
  (`objectValue`, `stringValue`, `get`, `Map.string(…)`, …) are extensions on `JsonElement`; RCCore's
  literals are `jsonOf`, `jsonObjectOf`, `jsonArrayOf`.

**Concurrency.** An actor with compound state (`GatewaySocket`, `STTSocket`, `DraftStore`,
`LocalCache`) confines it to its own `limitedParallelism(1)` dispatcher, which runs one piece of its
work at a time and lets another in at each suspension, as an actor does; `GatewayHTTPClient`'s only
state is a `@Volatile` token, `MemorySecretStore` a `Mutex`. Actor properties (`isConnected`,
`hasToken`) are plain reads. The socket loops `yield()` between frames, so a connection that always
has the next frame ready cannot starve a request waiting to be written. RCCore's continuations ignore
cancellation; these are cancellable — a cancelled caller stops waiting and its reply slot is dropped.
`AsyncStream(bufferingNewest:)` is a `Channel(DROP_OLDEST)` read as a `Flow`
(`EventBuffer.Stream(stream, continuation)`), one reader per stream as in RCCore.

**Transport on OkHttp.** `HTTPTransport.perform(Request): Pair<ByteArray, Response>` and
`WebSocketFactory.makeConnection(Request)` take OkHttp's request as RCCore's take `URLRequest`, so
OkHttp is an `api` dependency. `URLSessionHTTPTransport` is `OkHttpHTTPTransport` (no redirects,
30 s timeouts, 8 MiB cap) and `URLSessionWebSocketFactory` is `OkHttpWebSocketFactory` (a socket this
app closes sends its close frame and is gone within a second). An empty POST or PATCH carries an empty
body, as `URLSession` sends. A path segment is percent-encoded once — RCCore's `appending(path:)`
double-encodes a reserved ASCII character, which no gateway id contains — and OkHttp writes `+` in a
query as `%2B` where `URLComponents` leaves it. `GatewayEndpoint` reads an address with its own
RFC 3986 reader, because `java.net.URI` refuses hosts `URLComponents` takes; `TransportChecks` pins
Foundation's answers on macOS 27 for every rule. Logging is `java.util.logging`, which reaches logcat.

**Platform seams.** `SecretStore` is the interface with `MemorySecretStore`; the keychain store is not
ported, and `GatewayHTTPClient(endpoint, transport, secrets)` has no default secret store — each app
passes its own (Android Keystore, Windows DPAPI). `DraftStore(directory)` and `LocalCache(directory)`
take their root; RCCore's default is Application Support's `RemoteControl/Drafts` and
`RemoteControl/Cache`. Files are written atomically into that directory; keeping them out of backups
is the app's to declare.

**Words.** `L10n` keeps RCCore's shape (`use`, `string(key)`, `string(key, args…)`); the table,
`Localizable.zhHans`, holds all 139 keys RCCore looks up — the stores' and the demo's too — with the
iPhone catalogue's values, which `LocalizationTests` holds it to, along with every `L10n.string`
literal in these sources. Keys keep Swift's specifiers (`%@`, `%lld`); `formatted()` is
`String(format:)`: the POSIX locale, and a fraction exactly between two printable values rounded to
the even one, as `printf` does. `VoiceBackend.onDevice.title` is RCCore's "On this iPhone"; an app
that means another device says so in its own words. `Character.isNumber`, `CharacterSet.whitespaces`
and the like are re-implemented in `FoundationText.kt`; `String.trimmed` (`Trimming.swift`) should
use `trimmingWhitespacesAndNewlines()`.

**This build's version (A46).** `AppBuild.shipped = "1.12.0"`, which the release bump moves;
`AppBuild.version` is `@Volatile var`, `shipped` until the app sets it once at startup
(`BuildConfig.VERSION_NAME` on Android, the packaged version on Windows). That keeps
`AppUpdateRequirement.of(apps, app = InstalledApp.ios, current = AppBuild.version)` as RCCore's; the
Android and Windows apps pass `app = InstalledApp.android` / `.windows`, as the Mac app passes
`.macos`. `InstalledApp` has all four cases and `AppsInfo` all four entries.

**Markdown.** The parser reads Unicode scalars where RCCore reads Swift characters; the two differ
only where a combining mark sits directly on a Markdown marker. `MarkdownDocument(source, isCancelled)`
takes the check RCCore makes with `Task.isCancelled`. `MarkdownParityTests` holds the port to
RCCore's own parse of 89 documents, 37 math inputs and 12 links (`src/test/resources/markdown`).

## What the next agents build on

- `protocol`: every wire type (`Device`, `AgentInfo`, `Session`, `SessionEvent` and `SessionEventBody`,
  the payloads, `AppFrame`, `HelloFrame`, `Preferences`/`PreferencePatch`, the reply results,
  `GatewayRequest` and its builders, `SpeedChange`, `RequestLimits`, `TerminalLimits`, `ResumeBounds`,
  `AccountRules`, the `WireEnum` values), `WireJson`, and the `JSONValue` helpers.
- `transport`: `GatewayHTTPClient` (a `GatewayAPI`), `GatewaySocket` (a `GatewayChannel`) with
  `GatewayEvent`, `ConnectionState` and `SocketCloseReason`, `STTSocket` with `STTEvent`,
  `GatewayEndpoint`, `TransportError`, the HTTP models, and the seams `HTTPTransport`,
  `WebSocketFactory` and `WebSocketConnection` a fake implements.
- `persistence`: `LocalCache` with `CachedWorkspace`, `DraftStore`, `SecretStore`.
- `state`: `GatewayChannel` (with `request(request, type)` for RCCore's `request(_:as:)`),
  `GatewayAPI`, `L10n`/`InterfaceLanguage`, `AppVersion`/`AppBuild`/`InstalledApp`/`AppUpdateRequirement`,
  `DictationLanguage`, `VoiceBackend`, `TimelineDetail`, `RelativeTime`.
- `markdown`: `MarkdownDocument`, `MarkdownBlock`, `MarkdownMath`, `MarkdownLink`.

## Tests

RCCoreTests suites whose subject is here are ported whole: `ProtocolTests`, `TransportTests`,
`EventBufferTests`, `PersistenceTests`. These are ported for their wire cases, and each file says
which cases, by the stores and the demo they drive, are the next agents' to add: `AmendmentTests`
(with `TerminalAndOrderingTests`), `DictationLanguageTests`, `PolishTests` (`AppVersionTests`,
`AgentMessageTests`), `AccountsTests`, `AgentAccountsTests`, `AgentsTests`, `CodexDaemonTests`,
`GrokLeaderTests`, `SharedControlTests`, `TypedTerminalTests`, `UsageLimitTests`, `SlashCommandTests`,
`QueuedEditTests`, `QuestionAnswerTests`, `DeviceUpdateTests`, `ReviewFixTests`. The rest drive stores
alone and are `core-state`'s and `core-demo`'s.

`ios/Verification`'s checks become JUnit tests of the same names: `ProtocolChecks` (with
`ProtocolFrameChecks`, `ProtocolRequestChecks`: every fixture under `protocol/fixtures` decodes and
every event re-encodes without losing a field), `TransportChecks`, `SocketChecks`, `STTChecks`,
`PersistenceChecks`, `MarkdownChecks`, `AccountChecks`, `PolishChecks` and `LocalizationTests`; their
lines that read a store (`DeviceUpdate`, `PairingClaimLink`, `CommandSection`, `AccountError`,
`DictationPolish`, …) are `core-state`'s. `OkHttpTransportTests` drives the real transport and both
sockets against `mockwebserver3`, and `LiveGatewayTests` against a running gateway.

## State

The stores and the rules they read: every file of RCCore's `State/` the first half did not take,
one Kotlin file per Swift file. Where a Swift file ran long, a part went to a file of its own:
`ConnectionPhase` (from `ConnectionStore.swift`), `PendingSend`, `PolishPhase` and the composer's
rules in `ChatStoreRules.kt` (from `ChatStore.swift`), `OptimisticMessage` and `TimelineEntry` (from
`Timeline.swift`). `StoreSupport.kt` and `Characters.kt` are internal: Swift's `didSet`,
`Task.isCancelled` and `localizedDescription`, and Swift's `Character`.

**Stores.** Each is a plain class; what a screen reads is snapshot state (`var x by mutableStateOf(…);
private set`), and a property RCCore observes with `didSet` is an `ObservedValue` that calls back
after the write. A store that starts work takes the scope it runs in as `tasks` — `ConnectionStore`,
`ChatStore`, `TerminalSession`, `PreferenceSync` — and RCCore's `Task { … }` is `tasks.launch { … }`;
the others only suspend. A store's suspend functions rethrow cancellation, so a request cancelled
with its caller is a request nobody waits for: an app starts what a person asked for (a send, an
approval) in a scope that outlives the screen, as SwiftUI's unstructured tasks do. In tests the
stores run on `backgroundScope` of `runTest`.

**Observers.** Where RCCore follows a value with `withObservationTracking`, the port reads it inside
a `snapshotFlow` in the scope it is handed: `SeenReporter` (A47) watches the conversation an app says
is in front and its mark, and the apps' badge keepers follow the count the same way. A snapshot
written with an equal value reports nothing, where an observed Swift property reports every
assignment, so `SeenReporter` reads the connection being up as a moment of its own (a `hello` that
changes nothing still brings the socket back). Something has to tell snapshot observers what changed:
the apps' frame clocks do, and a test calls `Snapshot.sendApplyNotifications()` as it waits.

**Values.** A struct RCCore changes with `mutating` methods is immutable here where a store publishes
it — `QuestionDraft.toggle` and `setText` answer the new draft, `TimelineEntry.merge` the new entry —
so the copy a screen holds never changes under it. Two are changed in place and offer `copy()` for
the value an assignment makes: `TranscriptSegments`, which a recogniser owns, and `Timeline`, which is
thousands of rows and changes with every streaming delta. A `Timeline` is observable itself: every
read goes through its `version`, which is snapshot state bumped by every mutation, so whoever reads
any of it is redrawn when any of it moves. `ControlLatch` keeps `isArmed` as snapshot state for the
screen that `remember`s one. `[UInt8]` is `ByteArray`, and `TerminalKey.bytes` is a fresh array each
time.

**Seams and defaults.** `UserDefaults` is the seam for Foundation's (`SharedPreferences` on Android,
the profile on Windows; `MemoryUserDefaults` for tests), read as Foundation reads it: absent is null,
false or zero. `ConnectionStore` has no default cache directory or HTTP client, because both need
the app's own (a directory, a secret store); `makeChannel` defaults to a `GatewaySocket` over the
client, or over a client with no token, as RCCore's fallback is. `ConnectionStore.offlineDemo(tasks,
cache, installedApp, registrationOpen)` is RCCore's, with the two the app always hands in.

**Words and clocks.** Every sentence is RCCore's, through `L10n`. Initials, a command draft, a
transcript's shape and a vendor's plan count characters as Swift does, one extended grapheme
cluster each (`BreakIterator`). A `Calendar` parameter is a `ZoneId` (the calendar is always the
Gregorian one) beside the `Locale`. `QuotaWindow` keeps RCCore's fixed patterns; `ResumeText`'s clock
is the locale's short time, and for another day the locale's medium date with the year taken out in
front of it, which is what `Date.FormatStyle`'s month-and-day gives without the skeletons Android 29
lacks. The diagnostic report names the app that wrote it — RCCore's says iOS for the iPhone and the
Mac alike — so `diagnosticReport(app, …)` takes the installed app (round 56's ruling).

**Tests.** Every RCCoreTests suite whose subject is in `State/` is ported under `state/`, case names
as RCCore's. A suite whose wire half the first half ported keeps that half in `protocol/` and its
store cases here, under the same class name (`state.AmendmentTests` beside `protocol.AmendmentTests`).
`ios/Verification`'s `StoreChecks`, `TimelineChecks`, `AlertChecks` and the store lines of
`AccountChecks`, `PolishChecks` and `ProtocolChecks` are JUnit checks of the same names, with
`SettingsScreenChecks` and `OfflineDemoChecks` for the lines of `VerificationUI` that read core
types, `UnseenChecks` for A47's (with RCCore's `UnseenMarkTests` split across `protocol/`, `state/`
and `demo/`), and `DiagnosticReportTests` for the report's app. `StoreDoubles.kt` holds the doubles every
suite would otherwise spell out (`StubGateway`, `InertChannel`) and RCCore's polling `settle`, on the
test's clock; a suite's own doubles are nested in it. A case RCCore runs on the demo gateway runs on
`demoGateway(…)` with the same arguments, on virtual time: RCCore's `DemoGateway()` is
`demoGateway(resumeDelay = DemoGateway.defaultResumeDelay)`, since the test helper defaults to
RCCore's `resumeDelay: nil`. The demo's own cases of a suite are in `demo/` under the same name, the
wire's in `protocol/`.

## Demo

`…core.demo` is the port of RCCore's `Demo/`: `DemoGateway`, the in-memory gateway every demo mode,
preview, screenshot and UI test runs on, and the `DemoFixtures`, `DemoQueue`, `DemoShell` and
`DemoDirectoryTree` it serves from — the same devices, sessions, transcripts, agents, accounts,
quotas, directories, held messages, delays and replies, so an Android or Windows screenshot of the
demo shows what an iPhone or Mac screenshot of it shows. A46: its `apps` carries the Android and
Windows entries beside the iPhone's and the Mac's, all four the same; its served client is
`AppBuild.version`, read when asked, so it follows what the app states at startup.

What the contract did not settle was decided as follows.

- **One object, split by subject.** `DemoFixtures` keeps the identifiers and plain values and
  implements one sealed interface per subject (`DemoAgentFixtures`, `DemoQuotaFixtures`,
  `DemoCommandFixtures`, `DemoSessionFixtures`, `DemoHistoryFixtures` and
  `DemoTerminalHistoryFixtures` for the sessions a terminal holds or shares, `DemoGatewayFixtures`),
  so a fixture is still `DemoFixtures.claude`, with nothing to import. Swift labels that are Kotlin
  keywords give way to the internal name: `history(sessionID)`, `commands(agent)`.
- **One class, its handlers beside it.** Kotlin has no partial classes, so `DemoGateway.kt` holds
  the state and the `GatewayChannel` and `GatewayAPI` members, and the requests RCCore's actor
  answers are extension functions in `DemoGatewaySessions.kt`, `DemoGatewayTurns.kt`,
  `DemoGatewayCommands.kt`, `DemoGatewayScripts.kt` and `DemoGatewayDevices.kt`, by RCCore's own
  sections. The state is `internal` for that reason alone; it is read and written only on the
  gateway's isolation, through `request`.
- **The actor's isolation is a parameter.** `DemoGateway(…, isolation =
  Dispatchers.Default.limitedParallelism(1))` runs one piece of work at a time, as the actor does.
  A test passes `StandardTestDispatcher(testScheduler)`, and every scripted delay — the echo, the
  turns, the injection, the 30-second terminal answer — runs on virtual time. `disconnect()`
  always runs to the end, as `GatewaySocket.disconnect()` does.
- **RCCore's pauses, exactly.** `try? await Task.sleep(for:)` is `pause(duration)`: cancellation
  cuts it short and what follows still runs, and the scripts check `isCancelled()` where RCCore
  checks `Task.isCancelled`. So the edge cases are RCCore's too: a pairing cancelled mid-step still
  sends that step, and a reply cancelled on its last word still finishes its turn.
- `events` buffers the oldest 512 (`Channel(512, DROP_LATEST)`), as RCCore's `bufferingOldest(512)`.
  RCCore's mutating structs `DemoQueue`, `DemoShell` and `DemoDirectoryTree` are classes changed in
  place, each owned by one gateway; `DemoShell.feed` answers with a `DemoShell.Response(output, code)`
  for RCCore's tuple. `queue_ts` is read as RCCore's `JSONValue.integer`: a whole number written as
  one, never `1.5`, `"12"` or `true`.

**Tests.** `DemoQueueTests` is ported whole; `editRoundTrip`, which drives `ChatStore`, lives in
`state/DemoQueueTests.kt`, and the same round trip at the gateway is
`DemoTurnTests.queuedEditGoesBackToItsPlace`.
The demo's own cases of RCCore's other suites are in this package under the suites' names
(`AgentsTests`, `AccountsTests`, `AgentAccountsTests`, `CodexDaemonTests`, `DeviceUpdateTests`,
`GrokLeaderTests`, `QuestionAnswerTests`, `AgentMessageTests`, `SharedControlTests`,
`TypedTerminalTests`, `SlashCommandTests`), with `PolishChecks.agentMessages` for the demo's line
of `ios/Verification`; their cases that drive a store are in the `state` package under the same
names, the `DeviceUpdate.notice` line of `demoRetry` among them. `DemoGatewayTests` drives the
gateway as a store does —
sign in, `hello`, subscribe, send, the scripted turns to their ends — and, with `DemoTurnTests` and
`DemoMachineTests`, pins what it does with queues, attached terminals, commands, resumes, shells,
folders, quotas and pairing. `DemoTestSupport.kt` builds a gateway on the test's clock and reads
its events the way a store's pump does.

The port was checked against RCCore itself, with scratch tools that are not in the repository:
every fixture dumped from both and compared field by field, and ten scripted scenarios — 757
events, replies and refusals, each timed script included — traced through both gateways and
compared line by line. Both matched; the only differences were A46's two new entries.
