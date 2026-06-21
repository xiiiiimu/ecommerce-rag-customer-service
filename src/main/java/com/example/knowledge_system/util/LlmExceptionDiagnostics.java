package com.example.knowledge_system.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

public final class LlmExceptionDiagnostics {

    private LlmExceptionDiagnostics() {
    }

    public static Throwable rootCause(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    public static String format(Throwable throwable) {
        if (throwable == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("exceptionChain=").append(chainSummary(throwable));
        Throwable root = rootCause(throwable);
        if (root != null && root != throwable) {
            sb.append(", rootCause=").append(root.getClass().getName())
                    .append(": ").append(root.getMessage());
        }
        sb.append("\n").append(stackTrace(throwable));
        return sb.toString();
    }

    private static String chainSummary(Throwable throwable) {
        List<String> parts = new ArrayList<>();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 8) {
            parts.add(current.getClass().getSimpleName() + ": " + current.getMessage());
            if (current.getCause() == null || current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return String.join(" -> ", parts);
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
