package com.winlator.xenvironment.components;

/** Pure predicate shared by the JNI-facing validator and host unit tests. */
final class WindowAuthorityEligibility {
    private WindowAuthorityEligibility() {}

    static boolean isEligible(
            boolean present,
            boolean root,
            boolean viewable,
            boolean hasOriginClient,
            boolean inputOutput,
            boolean hasContent,
            int width,
            int height,
            long lifetime) {
        return present && !root && viewable && hasOriginClient && inputOutput &&
                hasContent && width > 0 && height > 0 && lifetime > 0L;
    }
}
