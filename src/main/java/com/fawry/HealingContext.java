package com.fawry;

public class HealingContext {
    private static final ThreadLocal<Boolean> healingEnabled = ThreadLocal.withInitial(() -> true);

    public static void disableHealing() {
        healingEnabled.set(false);
    }

    public static void enableHealing() {
        healingEnabled.set(true);
    }

    public static boolean isHealingEnabled() {
        return healingEnabled.get();
    }
}
