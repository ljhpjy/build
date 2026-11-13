package com.dsmod.deepseek;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide counters shown by /healthz and the admin panel.
 *
 * They answer the questions a single request log cannot: how many tool calls
 * have run since startup, how many of them needed a non-DSML fallback (the
 * model ignoring the documented protocol), and how many invented a result.
 */
final class Stats {
    /** Every tool call returned to a client. */
    static final AtomicLong TOOL_CALLS = new AtomicLong();
    /** Subset of TOOL_CALLS parsed from a non-DSML syntax (channel/tooltoken/barejson). */
    static final AtomicLong TOOL_DOWNGRADED = new AtomicLong();
    /** Replies where the model wrote a tool result itself (fabricated). */
    static final AtomicLong FABRICATED = new AtomicLong();
    /** Requests that ended with an upstream/API error. */
    static final AtomicLong API_ERRORS = new AtomicLong();

    private Stats() {}
}
