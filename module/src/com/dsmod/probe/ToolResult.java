package com.dsmod.probe;

/**
 * Outcome of one tool invocation handed back to the model.
 *
 * <p>The heartbeat tool pipeline reports results through this type, so it lives in its own file
 * as shared plumbing instead of inside the retired local-tool bridge.</p>
 */
final class ToolResult {
    final boolean success;
    final int exitCode;
    final String output;
    final String detail;
    final String encoding;
    final boolean truncated;

    ToolResult(boolean success, int exitCode, String output,
               String detail, String encoding, boolean truncated) {
        this.success = success;
        this.exitCode = exitCode;
        this.output = output == null ? "" : output;
        this.detail = detail == null ? "" : detail;
        this.encoding = encoding == null ? "utf-8" : encoding;
        this.truncated = truncated;
    }
}
