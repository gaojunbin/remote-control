/**
 * The scripted live turn the mock gateway plays back: thinking, streamed text,
 * tool calls with live output, an approval and a question.
 */
import type { SessionEvent } from '../src/protocol/types';

export interface Step {
  after: number;
  event: (seq: number, ts: number) => SessionEvent;
}

const THINKING = [
  'The failure only shows up on CI, so the shared state is probably ',
  'a module-level clock. Let me reproduce with a repeat count first ',
  'and then look at how the session lock is acquired.',
];

const ANSWER = [
  'Reproducing first — the refresh test shares a module-level clock, ',
  'so a scheduled expiry can leak between tests.',
];

const CLOSING = [
  'Each test now gets its own frozen clock and refresh is guarded by ',
  'the session lock. Re-running the suite 100× to confirm.',
];

const LIVE_OUTPUT = [
  '.....................',
  '............... 42%\n',
  '............................. 72%\n',
  '72 passed in 38.02s\n',
];

export function turnScript(prompt: string): Step[] {
  const steps: Step[] = [];
  let at = 0;
  const push = (delay: number, event: Step['event']) => {
    at += delay;
    steps.push({ after: at, event });
  };

  push(0, (seq, ts) => ({ seq, ts, kind: 'turn_started', turn_id: 'turn-live', trigger: 'remote' }));
  push(20, (seq, ts) => ({
    seq,
    ts,
    kind: 'user_message',
    block_id: `u-${ts}`,
    source: 'remote',
    text: prompt,
  }));
  push(120, (seq, ts) => ({ seq, ts, kind: 'status', state: 'running' }));

  THINKING.forEach((delta, index) => {
    push(240, (seq, ts) => ({ seq, ts, kind: 'thinking', block_id: 'blk-think', delta, done: false }));
    void index;
  });
  push(200, (seq, ts) => ({
    seq,
    ts,
    kind: 'thinking',
    block_id: 'blk-think',
    text: THINKING.join(''),
    done: true,
    duration_ms: 12_000,
  }));

  ANSWER.forEach((delta) => {
    push(180, (seq, ts) => ({ seq, ts, kind: 'assistant_text', block_id: 'blk-text', delta, done: false }));
  });
  push(160, (seq, ts) => ({
    seq,
    ts,
    kind: 'assistant_text',
    block_id: 'blk-text',
    text: ANSWER.join(''),
    done: true,
  }));

  push(200, (seq, ts) => ({
    seq,
    ts,
    kind: 'todos',
    items: [
      { id: 'td1', text: 'Reproduce the flake locally', status: 'completed' },
      { id: 'td2', text: 'Isolate the shared clock', status: 'in_progress' },
      { id: 'td3', text: 'Guard the refresh path with the session lock', status: 'pending' },
      { id: 'td4', text: 'Re-run the suite 100 times', status: 'pending' },
    ],
  }));

  push(300, (seq, ts) => ({
    seq,
    ts,
    kind: 'tool_call',
    block_id: 'blk-read',
    tool: 'Read',
    tool_kind: 'read',
    title: '4 files in tests/ and auth/',
    status: 'succeeded',
    started_at: ts - 1_200,
    ended_at: ts,
    duration_ms: 1_200,
    input: { paths: ['tests/test_auth.py', 'tests/conftest.py', 'auth/session.py', 'auth/clock.py'] },
    output: 'tests/test_auth.py (218 lines)\ntests/conftest.py (44 lines)\nauth/session.py (312 lines)\nauth/clock.py (27 lines)',
  }));

  push(320, (seq, ts) => ({
    seq,
    ts,
    kind: 'tool_call',
    block_id: 'blk-bash-1',
    tool: 'Bash',
    tool_kind: 'shell',
    title: 'pytest -k refresh --count 20',
    status: 'failed',
    started_at: ts - 6_400,
    ended_at: ts,
    duration_ms: 6_400,
    summary: '2 failed',
    input: { command: 'pytest -k refresh --count 20' },
    output:
      'FAILED tests/test_auth.py::test_refresh_flow - AssertionError: token expired\nFAILED tests/test_auth.py::test_refresh_flow - AssertionError: token expired\n2 failed, 18 passed in 6.40s',
  }));

  push(280, (seq, ts) => ({
    seq,
    ts,
    kind: 'tool_call',
    block_id: 'blk-edit-1',
    tool: 'Edit',
    tool_kind: 'edit',
    title: 'auth/session.py',
    status: 'succeeded',
    started_at: ts - 400,
    ended_at: ts,
    duration_ms: 400,
    diff: {
      path: 'auth/session.py',
      additions: 12,
      deletions: 4,
      patch: `--- a/auth/session.py
+++ b/auth/session.py
@@ -41,10 +41,18 @@ class SessionStore:
-    _clock = time.monotonic
-
-    def refresh(self, token: str) -> Token:
-        if self._expired(token):
-            raise TokenExpired(token)
+    def __init__(self, clock: Clock | None = None) -> None:
+        self._clock = clock or SystemClock()
+        self._lock = threading.RLock()
+
+    def refresh(self, token: str) -> Token:
+        with self._lock:
+            if self._expired(token):
+                raise TokenExpired(token)
+            return self._rotate(token)`,
    },
  }));

  push(240, (seq, ts) => ({
    seq,
    ts,
    kind: 'tool_call',
    block_id: 'blk-edit-2',
    tool: 'Edit',
    tool_kind: 'edit',
    title: 'tests/conftest.py',
    status: 'succeeded',
    started_at: ts - 300,
    ended_at: ts,
    duration_ms: 300,
    diff: {
      path: 'tests/conftest.py',
      additions: 8,
      deletions: 1,
      patch: `--- a/tests/conftest.py
+++ b/tests/conftest.py
@@ -1,5 +1,12 @@
-import pytest
+import pytest
+
+from auth.clock import FrozenClock
+
+
+@pytest.fixture
+def frozen_clock():
+    return FrozenClock(start=0.0)`,
    },
  }));

  CLOSING.forEach((delta) => {
    push(200, (seq, ts) => ({ seq, ts, kind: 'assistant_text', block_id: 'blk-text-2', delta, done: false }));
  });
  push(160, (seq, ts) => ({
    seq,
    ts,
    kind: 'assistant_text',
    block_id: 'blk-text-2',
    text: CLOSING.join(''),
    done: true,
  }));

  push(220, (seq, ts) => ({
    seq,
    ts,
    kind: 'tool_call',
    block_id: 'blk-bash-live',
    tool: 'Bash',
    tool_kind: 'shell',
    title: 'pytest tests/test_auth.py --count 100 -q',
    status: 'running',
    started_at: ts,
    input: { command: 'pytest tests/test_auth.py --count 100 -q' },
    output: '',
  }));

  let live = '';
  LIVE_OUTPUT.forEach((chunk) => {
    push(700, (seq, ts) => {
      live += chunk;
      return {
        seq,
        ts,
        kind: 'tool_call',
        block_id: 'blk-bash-live',
        tool: 'Bash',
        tool_kind: 'shell',
        title: 'pytest tests/test_auth.py --count 100 -q',
        status: 'running',
        started_at: ts - 2_000,
        input: { command: 'pytest tests/test_auth.py --count 100 -q' },
        output: live,
      };
    });
  });

  push(600, (seq, ts) => ({
    seq,
    ts,
    kind: 'approval',
    block_id: 'blk-approval',
    request_id: 'req-live-1',
    tool: 'Bash',
    tool_kind: 'shell',
    title: 'git commit -am "fix: isolate the auth clock"',
    status: 'pending',
    input: { command: 'git commit -am "fix: isolate the auth clock"' },
    options: [
      { id: 'allow', label: 'Allow once', style: 'primary' },
      { id: 'allow_session', label: 'Allow for session', style: 'secondary' },
      { id: 'deny', label: 'Deny', style: 'danger' },
    ],
  }));
  push(20, (seq, ts) => ({ seq, ts, kind: 'status', state: 'needs_approval' }));

  return steps;
}

/** Emitted once the approval is answered. */
export function afterApproval(): Step[] {
  const steps: Step[] = [];
  let at = 0;
  const push = (delay: number, event: Step['event']) => {
    at += delay;
    steps.push({ after: at, event });
  };

  push(100, (seq, ts) => ({ seq, ts, kind: 'status', state: 'running' }));
  push(400, (seq, ts) => ({
    seq,
    ts,
    kind: 'tool_call',
    block_id: 'blk-bash-live',
    tool: 'Bash',
    tool_kind: 'shell',
    title: 'pytest tests/test_auth.py --count 100 -q',
    status: 'succeeded',
    started_at: ts - 40_000,
    ended_at: ts,
    duration_ms: 40_000,
    summary: '100 passed',
    output: '100 passed in 41.10s\n',
  }));
  push(400, (seq, ts) => ({
    seq,
    ts,
    kind: 'question',
    block_id: 'blk-question',
    request_id: 'req-live-2',
    status: 'pending',
    questions: [
      {
        id: 'q1',
        prompt: 'Should I also backport the fix to the release branch?',
        options: [
          { id: 'yes', label: 'Yes, open a cherry-pick', description: 'Creates a branch from release/1.4' },
          { id: 'no', label: 'No, main only' },
        ],
        multi: false,
        allow_text: true,
      },
    ],
  }));
  push(20, (seq, ts) => ({ seq, ts, kind: 'status', state: 'needs_input' }));

  return steps;
}

/** Emitted once the question is answered: the turn finishes. */
export function afterAnswer(): Step[] {
  const steps: Step[] = [];
  let at = 0;
  const push = (delay: number, event: Step['event']) => {
    at += delay;
    steps.push({ after: at, event });
  };

  push(100, (seq, ts) => ({ seq, ts, kind: 'status', state: 'running' }));
  push(300, (seq, ts) => ({
    seq,
    ts,
    kind: 'todos',
    items: [
      { id: 'td1', text: 'Reproduce the flake locally', status: 'completed' },
      { id: 'td2', text: 'Isolate the shared clock', status: 'completed' },
      { id: 'td3', text: 'Guard the refresh path with the session lock', status: 'completed' },
      { id: 'td4', text: 'Re-run the suite 100 times', status: 'completed' },
    ],
  }));
  push(300, (seq, ts) => ({
    seq,
    ts,
    kind: 'assistant_text',
    block_id: 'blk-text-3',
    done: true,
    text: 'Done. `auth/session.py` now takes an injectable clock and the refresh path holds the session lock:\n\n```python\nwith self._lock:\n    if self._expired(token):\n        raise TokenExpired(token)\n    return self._rotate(token)\n```\n\n100 consecutive runs are green.',
  }));
  push(200, (seq, ts) => ({
    seq,
    ts,
    kind: 'turn_completed',
    turn_id: 'turn-live',
    stop_reason: 'completed',
    duration_ms: 68_000,
    usage: {
      input_tokens: 44_100,
      output_tokens: 11_300,
      total_tokens: 55_400,
      context_used: 66_000,
      context_window: 200_000,
      cost_usd: 0.51,
    },
  }));
  push(20, (seq, ts) => ({ seq, ts, kind: 'status', state: 'idle' }));

  return steps;
}
