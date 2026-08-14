package com.playbackgate.subscription.repository;

import com.playbackgate.subscription.domain.Subscription;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long>, SubscriptionLockQuery {
}
