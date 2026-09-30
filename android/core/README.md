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
