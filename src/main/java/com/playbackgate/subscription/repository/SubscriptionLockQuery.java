package com.playbackgate.subscription.repository;

import com.playbackgate.subscription.domain.Subscription;
import java.util.Optional;

public interface SubscriptionLockQuery {

    Optional<Subscription> findLatestByMemberIdForUpdate(Long memberId);
}
