package com.playbackgate.subscription.repository;

import com.playbackgate.subscription.domain.Subscription;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;

public class SubscriptionLockQueryImpl implements SubscriptionLockQuery {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Optional<Subscription> findLatestByMemberIdForUpdate(Long memberId) {
        List<Subscription> found = entityManager
                .createQuery(
                        "select s from Subscription s where s.member.id = :memberId order by s.id desc",
                        Subscription.class
                )
                .setParameter("memberId", memberId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setMaxResults(1)
                .getResultList();
        return found.stream().findFirst();
    }
}
