package com.playbackgate.subscription.domain;

public enum SubscriptionPlan {
    BASIC(1),
    STANDARD(2),
    PREMIUM(4);

    private final int maxConcurrentPlayback;

    SubscriptionPlan(int maxConcurrentPlayback) {
        this.maxConcurrentPlayback = maxConcurrentPlayback;
    }

    public int getMaxConcurrentPlayback() {
        return maxConcurrentPlayback;
    }

    public boolean covers(SubscriptionPlan requiredPlan) {
        return this.ordinal() >= requiredPlan.ordinal();
    }
}
