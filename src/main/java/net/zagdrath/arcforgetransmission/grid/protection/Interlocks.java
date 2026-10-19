/*
 * Copyright (C), 2026 Zagdrath. Some rights reserved. This work is
 * licensed under the terms of the MIT license which can be found in the
 * root directory of this project.
 */

package net.zagdrath.arcforgetransmission.grid.protection;

import org.jspecify.annotations.Nullable;

// What a switching device may do, so no one opens a line under load or closes onto a fault:
//  - a disconnect switch only operates with every breaker it's interlocked with open (one in an unloaded chunk counts
//    as closed: it can't be seen to be open), and never closes onto a grounded line;
//  - a ground switch only closes on a dead line (opening it is always allowed);
//  - a breaker refuses to close while a ground switch is closed on either side of it.
public final class Interlocks {
    public enum Refusal {
        BREAKER_CLOSED("breaker_closed"), LINE_LIVE("line_live"), LINE_GROUNDED("line_grounded");

        private final String key;

        Refusal(String key) {
            this.key = key;
        }

        // The lang key's last part (message.arcforgetransmission.interlock.<key>).
        public String key() {
            return key;
        }
    }

    private Interlocks() {}

    public static @Nullable Refusal disconnect(boolean anyLinkedBreakerClosed) {
        return disconnect(anyLinkedBreakerClosed, false, false);
    }

    public static @Nullable Refusal disconnect(boolean anyLinkedBreakerClosed, boolean closing, boolean groundedEitherSide) {
        if (anyLinkedBreakerClosed) {
            return Refusal.BREAKER_CLOSED;
        }
        return closing && groundedEitherSide ? Refusal.LINE_GROUNDED : null;
    }

    public static @Nullable Refusal groundSwitch(boolean closing, boolean lineLive) {
        return closing && lineLive ? Refusal.LINE_LIVE : null;
    }

    public static @Nullable Refusal closeBreaker(boolean groundedEitherSide) {
        return groundedEitherSide ? Refusal.LINE_GROUNDED : null;
    }
}
