package com.voxelmind.local.provider;

/** A service turned the stored key or sign-in down (HTTP 401), as opposed to not answering right now. */
public final class Rejected extends IllegalStateException {
    public Rejected(String message) {
        super(message);
    }

    /** Whether a (possibly wrapped) failure is a rejection. */
    public static boolean is(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof Rejected) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** The innermost message of a failure, for "could not check right now: …". */
    public static String message(Throwable error) {
        Throwable t = error;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
