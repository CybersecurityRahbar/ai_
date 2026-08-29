# Conversation Ledger Rule

This file is a durable project rule requested by the user on 2026-08-29.

For every substantive project turn, append to the durable context ledger:
1. the user's request/message (or a faithful verbatim capture when available),
2. the assistant's resulting decision/answer/work performed,
3. relevant research findings and verification results,
4. build/test failures and fixes,
5. the current objective, constraints, and next action.

The ledger is a project artifact, not a replacement for source code. It must never be used as an excuse to omit code review, tests, or technical verification.

When an older conversation is only partially available, do not fabricate missing verbatim text. Preserve known facts as a clearly marked summary and continue capturing new turns exactly/faithfully from that point onward.
