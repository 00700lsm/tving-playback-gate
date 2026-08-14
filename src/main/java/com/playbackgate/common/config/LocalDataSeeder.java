package com.playbackgate.common.config;

import com.playbackgate.content.domain.Content;
import com.playbackgate.content.domain.ContentStatus;
import com.playbackgate.content.repository.ContentRepository;
import com.playbackgate.member.domain.Member;
import com.playbackgate.member.domain.MemberStatus;
import com.playbackgate.member.repository.MemberRepository;
import com.playbackgate.subscription.domain.Subscription;
import com.playbackgate.subscription.domain.SubscriptionPlan;
import com.playbackgate.subscription.domain.SubscriptionStatus;
import com.playbackgate.subscription.repository.SubscriptionRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
@Order(1)
public class LocalDataSeeder implements ApplicationRunner {

    private final MemberRepository memberRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final ContentRepository contentRepository;

    public LocalDataSeeder(
            MemberRepository memberRepository,
            SubscriptionRepository subscriptionRepository,
            ContentRepository contentRepository
    ) {
        this.memberRepository = memberRepository;
        this.subscriptionRepository = subscriptionRepository;
        this.contentRepository = contentRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (memberRepository.count() > 0) {
            return;
        }

        LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime started = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime expires = LocalDateTime.of(2026, 12, 31, 23, 59, 59);
        LocalDateTime availableFrom = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime availableUntil = LocalDateTime.of(2026, 12, 31, 23, 59, 59);

        Member premium = saveMember("premium@example.com", LocalDate.of(1990, 1, 1), MemberStatus.ACTIVE, now);
        Member basic = saveMember("basic@example.com", LocalDate.of(1990, 1, 1), MemberStatus.ACTIVE, now);
        Member standard = saveMember("standard@example.com", LocalDate.of(1990, 1, 1), MemberStatus.ACTIVE, now);
        Member blocked = saveMember("blocked@example.com", LocalDate.of(1990, 1, 1), MemberStatus.BLOCKED, now);
        Member withdrawn = saveMember("withdrawn@example.com", LocalDate.of(1990, 1, 1), MemberStatus.WITHDRAWN, now);
        Member expiredSub = saveMember("expired-sub@example.com", LocalDate.of(1990, 1, 1), MemberStatus.ACTIVE, now);
        Member suspended = saveMember("suspended@example.com", LocalDate.of(1990, 1, 1), MemberStatus.ACTIVE, now);
        Member young = saveMember("young@example.com", LocalDate.of(2009, 1, 1), MemberStatus.ACTIVE, now);

        saveSubscription(premium, SubscriptionPlan.PREMIUM, SubscriptionStatus.ACTIVE, started, expires);
        saveSubscription(basic, SubscriptionPlan.BASIC, SubscriptionStatus.ACTIVE, started, expires);
        saveSubscription(standard, SubscriptionPlan.STANDARD, SubscriptionStatus.ACTIVE, started, expires);
        saveSubscription(blocked, SubscriptionPlan.PREMIUM, SubscriptionStatus.ACTIVE, started, expires);
        saveSubscription(withdrawn, SubscriptionPlan.PREMIUM, SubscriptionStatus.ACTIVE, started, expires);
        saveSubscription(
                expiredSub,
                SubscriptionPlan.PREMIUM,
                SubscriptionStatus.EXPIRED,
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59, 59)
        );
        saveSubscription(suspended, SubscriptionPlan.PREMIUM, SubscriptionStatus.SUSPENDED, started, expires);
        saveSubscription(young, SubscriptionPlan.PREMIUM, SubscriptionStatus.ACTIVE, started, expires);

        contentRepository.save(new Content(
                "Basic Movie", ContentStatus.OPEN, 0, SubscriptionPlan.BASIC,
                availableFrom, availableUntil, now
        ));
        contentRepository.save(new Content(
                "Standard Show", ContentStatus.OPEN, 15, SubscriptionPlan.STANDARD,
                availableFrom, availableUntil, now
        ));
        contentRepository.save(new Content(
                "Premium Adult", ContentStatus.OPEN, 18, SubscriptionPlan.PREMIUM,
                availableFrom, availableUntil, now
        ));
        contentRepository.save(new Content(
                "Closed Film", ContentStatus.CLOSED, 0, SubscriptionPlan.BASIC,
                availableFrom, availableUntil, now
        ));
        contentRepository.save(new Content(
                "Upcoming Title", ContentStatus.OPEN, 0, SubscriptionPlan.BASIC,
                LocalDateTime.of(2027, 1, 1, 0, 0),
                LocalDateTime.of(2027, 12, 31, 23, 59, 59),
                now
        ));
        contentRepository.save(new Content(
                "Ended Title", ContentStatus.OPEN, 0, SubscriptionPlan.BASIC,
                LocalDateTime.of(2025, 1, 1, 0, 0),
                LocalDateTime.of(2025, 12, 31, 23, 59, 59),
                now
        ));
    }

    private Member saveMember(String email, LocalDate birthDate, MemberStatus status, LocalDateTime createdAt) {
        return memberRepository.save(new Member(email, birthDate, status, createdAt));
    }

    private void saveSubscription(
            Member member,
            SubscriptionPlan plan,
            SubscriptionStatus status,
            LocalDateTime startedAt,
            LocalDateTime expiresAt
    ) {
        subscriptionRepository.save(new Subscription(member, plan, status, startedAt, expiresAt));
    }
}
