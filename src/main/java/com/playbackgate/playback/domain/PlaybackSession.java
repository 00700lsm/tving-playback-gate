package com.playbackgate.playback.domain;

import com.playbackgate.content.domain.Content;
import com.playbackgate.member.domain.Member;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "playback_session")
public class PlaybackSession {

    public static final int DEFAULT_TTL_HOURS = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true, length = 36)
    private String sessionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id", nullable = false)
    private Content content;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlaybackSessionStatus status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    protected PlaybackSession() {
    }

    public static PlaybackSession start(Member member, Content content, String deviceId, LocalDateTime now) {
        PlaybackSession session = new PlaybackSession();
        session.sessionId = UUID.randomUUID().toString();
        session.member = member;
        session.content = content;
        session.deviceId = deviceId;
        session.status = PlaybackSessionStatus.ACTIVE;
        session.startedAt = now;
        session.expiresAt = now.plusHours(DEFAULT_TTL_HOURS);
        return session;
    }

    public void end(LocalDateTime now) {
        this.status = PlaybackSessionStatus.ENDED;
        this.endedAt = now;
    }

    public boolean belongsTo(Long memberId) {
        return member.getId().equals(memberId);
    }

    public Long getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Member getMember() {
        return member;
    }

    public Content getContent() {
        return content;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public PlaybackSessionStatus getStatus() {
        return status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }
}
