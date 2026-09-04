package com.dailylanguage.practice.domain;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PracticeSessionTests {

    private static final UUID SESSION_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000031");
    private static final UUID TASK_ID = UUID.fromString("019cc10c-a56a-7000-8000-000000000032");
    private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-09-04T10:15:30.123Z");
    private static final OffsetDateTime LATER = OffsetDateTime.parse("2026-09-04T10:20:00.000Z");

    @Test
    void inProgressSessionAllowsOnlyStartedAt() {
        PracticeSession session = new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.IN_PROGRESS, STARTED_AT,
                Optional.empty(), Optional.empty());

        assertThat(session.status()).isEqualTo(PracticeSession.Status.IN_PROGRESS);
        assertThat(session.startedAt()).isEqualTo(STARTED_AT);
        assertThat(session.completedAt()).isEmpty();
        assertThat(session.abandonedAt()).isEmpty();
    }

    @Test
    void completedSessionRequiresCompletedAtAfterStartedAtAndNoAbandonedAt() {
        assertThat(new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.COMPLETED, STARTED_AT,
                Optional.of(LATER), Optional.empty()).completedAt()).contains(LATER);

        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.COMPLETED, STARTED_AT,
                Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must be present while status is COMPLETED");
        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.COMPLETED, STARTED_AT,
                Optional.of(STARTED_AT.minusSeconds(1)), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must not be before startedAt");
        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.COMPLETED, STARTED_AT,
                Optional.of(LATER), Optional.of(LATER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("abandonedAt must be empty while status is COMPLETED");
    }

    @Test
    void abandonedSessionRequiresAbandonedAtAfterStartedAtAndNoCompletedAt() {
        assertThat(new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.ABANDONED, STARTED_AT,
                Optional.empty(), Optional.of(LATER)).abandonedAt()).contains(LATER);

        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.ABANDONED, STARTED_AT,
                Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("abandonedAt must be present while status is ABANDONED");
        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.ABANDONED, STARTED_AT,
                Optional.of(LATER), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must be empty while status is ABANDONED");
        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.ABANDONED, STARTED_AT,
                Optional.empty(), Optional.of(STARTED_AT.minusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("abandonedAt must not be before startedAt");
        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.ABANDONED, STARTED_AT,
                Optional.of(LATER), Optional.of(LATER)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must be empty while status is ABANDONED");
    }

    @Test
    void rejectsMissingIdentityAndTimestamps() {
        assertThatThrownBy(() -> new PracticeSession(
                null, TASK_ID, PracticeSession.Status.IN_PROGRESS, STARTED_AT,
                Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("id must not be null");
        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, null, PracticeSession.Status.IN_PROGRESS, STARTED_AT,
                Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("taskId must not be null");
        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, null, STARTED_AT, Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("status must not be null");
        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.IN_PROGRESS, null,
                Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("startedAt must not be null");
        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.IN_PROGRESS, STARTED_AT,
                null, Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("completedAt must not be null");
        assertThatThrownBy(() -> new PracticeSession(
                SESSION_ID, TASK_ID, PracticeSession.Status.IN_PROGRESS, STARTED_AT,
                Optional.empty(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("abandonedAt must not be null");
    }

    @Test
    void learnerResponsePreservesExactTextWithoutNormalization() {
        String rawText = "  Could I have a medium coffee,\tplease?  ";

        PracticeSession.LearnerResponse response = new PracticeSession.LearnerResponse(
                SESSION_ID, "order-drink", rawText, STARTED_AT);

        // 原始文本原样保存：不 trim、不改大小写、不做 Unicode normalization。
        assertThat(response.learnerText()).isSameAs(rawText);
        assertThat(PracticeSession.LearnerResponse.isAcceptableLearnerText(rawText)).isTrue();
    }

    @Test
    void learnerResponseRejectsNullBlankAndOversizedText() {
        assertThatThrownBy(() -> new PracticeSession.LearnerResponse(
                SESSION_ID, "order-drink", null, STARTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("learnerText must not be null, blank");
        assertThatThrownBy(() -> new PracticeSession.LearnerResponse(
                SESSION_ID, "order-drink", "", STARTED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PracticeSession.LearnerResponse(
                SESSION_ID, "order-drink", " \t\r\n ", STARTED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        String maximumText = "a".repeat(PracticeSession.LearnerResponse.MAXIMUM_LEARNER_TEXT_CODE_POINTS);
        String oversizedText = "a".repeat(
                PracticeSession.LearnerResponse.MAXIMUM_LEARNER_TEXT_CODE_POINTS + 1);
        assertThat(new PracticeSession.LearnerResponse(
                SESSION_ID, "order-drink", maximumText, STARTED_AT).learnerText()).hasSize(2000);
        assertThatThrownBy(() -> new PracticeSession.LearnerResponse(
                SESSION_ID, "order-drink", oversizedText, STARTED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        // 上限按 Unicode code point 计数：surrogate pair 占两个 char 但只算一个 code point。
        String surrogatePairs = "\uD83D\uDE00".repeat(1000) + "x";
        assertThat(new PracticeSession.LearnerResponse(
                SESSION_ID, "order-drink", surrogatePairs, STARTED_AT).learnerText())
                .hasSize(2001);
    }

    @Test
    void isAcceptableLearnerTextMirrorsConstructorRules() {
        assertThat(PracticeSession.LearnerResponse.isAcceptableLearnerText("hi")).isTrue();
        assertThat(PracticeSession.LearnerResponse.isAcceptableLearnerText(null)).isFalse();
        assertThat(PracticeSession.LearnerResponse.isAcceptableLearnerText("")).isFalse();
        assertThat(PracticeSession.LearnerResponse.isAcceptableLearnerText("   ")).isFalse();
        assertThat(PracticeSession.LearnerResponse.isAcceptableLearnerText(
                "a".repeat(PracticeSession.LearnerResponse.MAXIMUM_LEARNER_TEXT_CODE_POINTS + 1)))
                .isFalse();
    }

    @Test
    void learnerResponseRequiresIdentifierShapedStepIdAndIdentity() {
        assertThatThrownBy(() -> new PracticeSession.LearnerResponse(
                null, "order-drink", "text", STARTED_AT))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("sessionId must not be null");
        assertThatThrownBy(() -> new PracticeSession.LearnerResponse(
                SESSION_ID, null, "text", STARTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("stepId must not be blank");
        assertThatThrownBy(() -> new PracticeSession.LearnerResponse(
                SESSION_ID, " order-drink", "text", STARTED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("surrounding whitespace");
        assertThatThrownBy(() -> new PracticeSession.LearnerResponse(
                SESSION_ID, "a".repeat(PracticeSession.LearnerResponse.MAXIMUM_STEP_ID_LENGTH + 1),
                "text", STARTED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PracticeSession.LearnerResponse(
                SESSION_ID, "order-drink", "text", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("submittedAt must not be null");
    }
}
