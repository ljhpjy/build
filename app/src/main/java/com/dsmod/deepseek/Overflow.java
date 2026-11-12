package com.dsmod.deepseek;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Long-context overflow: move older sections into a .txt attachment.
 *
 * Mirrors fuckds prompt/overflow.mjs:
 *   - split only on section boundaries (never in the middle of a message);
 *   - keep the newest sections in the input box (the question is at the end);
 *   - the built-in protocol header always stays in the body;
 *   - the last section itself too long -> keep its tail, head goes to the file;
 *   - body gets a ## CONTEXT_ATTACHMENT note telling the model to read it first.
 */
public final class Overflow {
    public static final class Plan {
        public String prompt = "";
        public String attachment;
        public String name;
        public int moved;
    }

    private static final String PROTOCOL_MARK = "You are a precise execution-focused assistant";

    private Overflow() {}

    public static Plan plan(String prompt, int maxChars, int maxAttachmentChars) {
        Plan plan = new Plan();
        plan.prompt = prompt;
        if (prompt == null || prompt.length() <= maxChars) return plan;

        String[] parts = prompt.split("(?=\\n## )");
        if (parts.length <= 1) {
            String head = prompt.substring(0, prompt.length() - maxChars);
            plan.attachment = head;
            plan.prompt = prompt.substring(prompt.length() - maxChars);
            plan.name = nameOf(head);
            plan.moved = 1;
            return plan;
        }

        int protocolIdx = -1;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].contains(PROTOCOL_MARK)) {
                protocolIdx = i;
                break;
            }
        }

        boolean[] dropped = new boolean[parts.length];
        StringBuilder attachment = new StringBuilder();
        int moved = 0;

        while (true) {
            String body = join(parts, dropped);
            if (body.length() <= maxChars) {
                plan.prompt = body;
                break;
            }
            int pick = -1;
            // keep parts[0]: it carries the "# Messages:" / first system block
            for (int i = 1; i < parts.length - 1; i++) {
                if (!dropped[i] && i != protocolIdx) {
                    pick = i;
                    break;
                }
            }
            if (pick < 0) {
                // Only the last section (or the protocol block) is left and too long.
                String last = parts[parts.length - 1];
                int keep = Math.max(1000, maxChars - 2000);
                if (last.length() > keep) {
                    String head = last.substring(0, last.length() - keep);
                    attachment.append(head);
                    moved++;
                    parts[parts.length - 1] = "\n## User:\n(较早内容过大，已截断；完整内容见附件。)\n"
                            + last.substring(last.length() - keep);
                }
                plan.prompt = join(parts, dropped);
                break;
            }
            dropped[pick] = true;
            attachment.append(parts[pick]);
            moved++;
        }

        if (plan.prompt.length() > maxChars) {
            String cut = plan.prompt.substring(plan.prompt.length() - maxChars);
            attachment.insert(0, plan.prompt, 0, plan.prompt.length() - maxChars);
            plan.prompt = cut;
            moved = Math.max(moved, 1);
        }

        if (moved <= 0) return plan;

        String content = "# 对话历史（较早部分）\n" + attachment;
        if (content.length() > maxAttachmentChars) {
            content = content.substring(0, Math.max(0, maxAttachmentChars));
        }
        plan.attachment = content;
        plan.name = nameOf(content);
        plan.moved = moved;

        String note = "\n## CONTEXT_ATTACHMENT:\n较早的 " + moved
                + " 个段落已作为附件 " + plan.name
                + " 上传，请先读取附件中的对话历史，再继续回答。\n";
        if (protocolIdx >= 0 && !dropped[protocolIdx]) {
            String protocol = parts[protocolIdx];
            int at = plan.prompt.indexOf(protocol);
            if (at >= 0) {
                int end = at + protocol.length();
                plan.prompt = plan.prompt.substring(0, end) + note + plan.prompt.substring(end);
            } else {
                plan.prompt = note + plan.prompt;
            }
        } else {
            plan.prompt = note + plan.prompt;
        }
        return plan;
    }

    private static String join(String[] parts, boolean[] dropped) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (!dropped[i]) sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static String nameOf(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", digest[i] & 0xFF));
            }
            return "context-" + sb + ".txt";
        } catch (Throwable t) {
            return "context-" + Long.toHexString(System.currentTimeMillis()) + ".txt";
        }
    }
}
