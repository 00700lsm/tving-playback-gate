package com.playbackgate.content.domain;

import com.playbackgate.subscription.domain.SubscriptionPlan;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "content")
public class Content {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ContentStatus status;

    @Column(name = "age_rating", nullable = false)
    private int ageRating;

    @Enumerated(EnumType.STRING)
    @Column(name = "required_plan", nullable = false)
    private SubscriptionPlan requiredPlan;

    @Column(name = "available_from", nullable = false)
    private LocalDateTime availableFrom;

    @Column(name = "available_until", nullable = false)
    private LocalDateTime availableUntil;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Content() {
    }

    public Content(
            String title,
            ContentStatus status,
            int ageRating,
            SubscriptionPlan requiredPlan,
            LocalDateTime availableFrom,
            LocalDateTime availableUntil,
            LocalDateTime createdAt
    ) {
        this.title = title;
        this.status = status;
        this.ageRating = ageRating;
        this.requiredPlan = requiredPlan;
        this.availableFrom = availableFrom;
        this.availableUntil = availableUntil;
        this.createdAt = createdAt;
    }

    public boolean isOpen() {
        return status == ContentStatus.OPEN;
    }

    public boolean isWithinAvailability(LocalDateTime now) {
        return !now.isBefore(availableFrom) && !now.isAfter(availableUntil);
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public ContentStatus getStatus() {
        return status;
    }

    public int getAgeRating() {
        return ageRating;
    }

    public SubscriptionPlan getRequiredPlan() {
        return requiredPlan;
    }

    public LocalDateTime getAvailableFrom() {
        return availableFrom;
    }

    public LocalDateTime getAvailableUntil() {
        return availableUntil;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
