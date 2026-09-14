package com.dailylanguage.planner.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.dailylanguage.content.domain.MaterialDifficulty;
import com.dailylanguage.content.domain.MaterialIdentity;

class PlanningRunTests {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID OTHER_PROFILE_ID = UUID.randomUUID();
    private static final UUID JOB_ID = UUID.randomUUID();
    private static final UUID TASK_ID = UUID.randomUUID();
    private static final OffsetDateTime CREATED_AT = OffsetDateTime.now();
    private static final OffsetDateTime COMPLETED_AT = CREATED_AT.plusSeconds(3);

    @Test
    void acceptsPendingRunSnapshot() {
        PlanningRun run = run(PlanningRun.Status.PENDING, 0L, 0L, Optional.empty());

        assertThat(run.status()).isEqualTo(PlanningRun.Status.PENDING);
        assertThat(run.completedAt()).isEmpty();
        assertThat(run.learningTaskId()).isEmpty();
        assertThat(run.fallbackReason()).isEmpty();
    }

    @Test
    void acceptsTerminalRunWithBoundTaskAndCompletion() {
        PlanningRun modelApplied = run(PlanningRun.Status.MODEL_APPLIED, 0L, 1L,
                Optional.of(COMPLETED_AT), Optional.of(TASK_ID), Optional.empty());

        assertThat(modelApplied.learningTaskId()).contains(TASK_ID);
        assertThat(modelApplied.fallbackReason()).isEmpty();

        PlanningRun fallbackApplied = run(PlanningRun.Status.FALLBACK_APPLIED, 1L, 1L,
                Optional.of(COMPLETED_AT), Optional.of(TASK_ID),
                Optional.of(PlanningRun.FallbackReason.WAIT_BUDGET_EXHAUSTED));

        assertThat(fallbackApplied.fallbackReason())
                .contains(PlanningRun.FallbackReason.WAIT_BUDGET_EXHAUSTED);
    }

    @Test
    void rejectsPendingRunWithAnyTerminalFact() {
        assertThatThrownBy(() -> run(PlanningRun.Status.PENDING, 0L, 0L,
                Optional.empty(), Optional.of(TASK_ID), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("learningTaskId must be empty while status is PENDING");
        assertThatThrownBy(() -> run(PlanningRun.Status.PENDING, 0L, 0L,
                Optional.empty(), Optional.empty(),
                Optional.of(PlanningRun.FallbackReason.MODEL_CALL_FAILED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fallbackReason must be empty while status is PENDING");
    }

    @Test
    void rejectsModelAppliedWithoutTaskCompletionOrWithReason() {
        assertThatThrownBy(() -> run(PlanningRun.Status.MODEL_APPLIED, 0L, 1L,
                Optional.of(COMPLETED_AT), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("learningTaskId must be present while status is MODEL_APPLIED");
        assertThatThrownBy(() -> run(PlanningRun.Status.MODEL_APPLIED, 0L, 1L,
                Optional.of(COMPLETED_AT), Optional.of(TASK_ID),
                Optional.of(PlanningRun.FallbackReason.MODEL_CALL_FAILED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fallbackReason must be empty while status is MODEL_APPLIED");
        assertThatThrownBy(() -> run(PlanningRun.Status.MODEL_APPLIED, 0L, 1L,
                Optional.empty(), Optional.of(TASK_ID), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must be present while status is MODEL_APPLIED");
    }

    @Test
    void rejectsFallbackAppliedWithoutTaskReasonOrCompletion() {
        assertThatThrownBy(() -> run(PlanningRun.Status.FALLBACK_APPLIED, 1L, 1L,
                Optional.of(COMPLETED_AT), Optional.empty(),
                Optional.of(PlanningRun.FallbackReason.MODEL_CALL_FAILED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("learningTaskId must be present while status is FALLBACK_APPLIED");
        assertThatThrownBy(() -> run(PlanningRun.Status.FALLBACK_APPLIED, 1L, 1L,
                Optional.of(COMPLETED_AT), Optional.of(TASK_ID), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("fallbackReason must be present while status is FALLBACK_APPLIED");
        assertThatThrownBy(() -> run(PlanningRun.Status.FALLBACK_APPLIED, 1L, 1L,
                Optional.empty(), Optional.of(TASK_ID),
                Optional.of(PlanningRun.FallbackReason.MODEL_CALL_FAILED)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must be present while status is FALLBACK_APPLIED");
    }

    @Test
    void rejectsNegativeVersionsAndTerminalFacts() {
        assertThatThrownBy(() -> run(PlanningRun.Status.PENDING, -1L, 0L, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("workflowVersion must not be negative");
        assertThatThrownBy(() -> run(PlanningRun.Status.PENDING, 0L, -1L, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("rowVersion must not be negative");
        assertThatThrownBy(() -> run(
                PlanningRun.Status.PENDING, 0L, 0L, Optional.of(CREATED_AT.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("completedAt must be empty while status is PENDING");
        assertThatThrownBy(() -> new PlanningRun(
                UUID.randomUUID(), USER_ID, PROFILE_ID, JOB_ID, PlanningRun.Status.PENDING,
                0L, 0L, CREATED_AT, null, Optional.empty(), Optional.empty()))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("completedAt must not be null");
    }

    @Test
    void snapshotAcceptsOrderedDeterministicCandidates() {
        PlanningCandidateSet candidateSet = candidateSet(PROFILE_ID, 3);

        PlanningRun.Snapshot snapshot = PlanningRun.Snapshot.fromCandidateSet(candidateSet);

        assertThat(snapshot.languageProfileId()).isEqualTo(PROFILE_ID);
        assertThat(snapshot.candidates())
                .extracting(PlanningRun.Candidate::index)
                .containsExactly(0, 1, 2);
        assertThat(snapshot.candidates())
                .extracting(PlanningRun.Candidate::identity)
                .containsExactlyElementsOf(candidateSet.candidates()
                        .stream()
                        .map(LearningTaskPlan::materialIdentity)
                        .toList());
    }

    @Test
    void snapshotAcceptsMaximumEightCandidates() {
        PlanningRun.Snapshot snapshot =
                PlanningRun.Snapshot.fromCandidateSet(candidateSet(PROFILE_ID, 8));

        assertThat(snapshot.candidates()).hasSize(PlanningRun.MAXIMUM_CANDIDATE_COUNT);
        assertThat(snapshot.candidates().getLast().index()).isEqualTo(7);
    }

    @Test
    void snapshotRejectsEmptyAndOversizedCandidateList() {
        assertThatThrownBy(() -> new PlanningRun.Snapshot(PROFILE_ID, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidates must not be empty");
        List<PlanningRun.Candidate> oversized = IntStream.range(0, 9)
                .mapToObj(sequence -> new PlanningRun.Candidate(0, identity(sequence)))
                .toList();
        assertThatThrownBy(() -> new PlanningRun.Snapshot(PROFILE_ID, oversized))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidates must not exceed "
                        + PlanningRun.MAXIMUM_CANDIDATE_COUNT + " entries");
    }

    @Test
    void snapshotRejectsNonDenseOrUnorderedIndex() {
        List<PlanningRun.Candidate> sparse = List.of(
                new PlanningRun.Candidate(0, identity(0)),
                new PlanningRun.Candidate(2, identity(1)));
        assertThatThrownBy(() -> new PlanningRun.Snapshot(PROFILE_ID, sparse))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate index must be dense, ordered and start at 0");
        List<PlanningRun.Candidate> unordered = List.of(
                new PlanningRun.Candidate(1, identity(0)),
                new PlanningRun.Candidate(1, identity(1)));
        assertThatThrownBy(() -> new PlanningRun.Snapshot(PROFILE_ID, unordered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate index must be dense, ordered and start at 0");
    }

    @Test
    void snapshotRejectsDuplicateMaterialIdentity() {
        List<PlanningRun.Candidate> duplicated = List.of(
                new PlanningRun.Candidate(0, identity(0)),
                new PlanningRun.Candidate(1, identity(0)));
        assertThatThrownBy(() -> new PlanningRun.Snapshot(PROFILE_ID, duplicated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidates must not contain duplicate material identity");
    }

    @Test
    void candidateIndexMustStayWithinClosedZeroToSevenRange() {
        assertThatThrownBy(() -> new PlanningRun.Candidate(8, identity(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate index must be between 0 and 7");
        assertThatThrownBy(() -> new PlanningRun.Candidate(-1, identity(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate index must be between 0 and 7");
        assertThatThrownBy(() -> new PlanningRun.Candidate(0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("identity must not be null");
    }

    @Test
    void fromCandidateSetRejectsMixedProfiles() {
        List<LearningTaskPlan> mixed = List.of(
                deterministicPlan(PROFILE_ID, 0),
                deterministicPlan(OTHER_PROFILE_ID, 1));
        PlanningCandidateSet candidateSet = new PlanningCandidateSet(mixed);

        assertThatThrownBy(() -> PlanningRun.Snapshot.fromCandidateSet(candidateSet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate plans must share one languageProfileId");
    }

    @Test
    void fromCandidateSetRejectsEnrichedPlan() {
        LearningTaskPlan enrichedPlan = new LearningTaskPlan(
                PROFILE_ID,
                identity(0),
                "en",
                "zh",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.MODEL_ENRICHED,
                Optional.of("今天在咖啡馆练习点单，并追问今日特供。"));
        PlanningCandidateSet candidateSet =
                new PlanningCandidateSet(List.of(deterministicPlan(PROFILE_ID, 1), enrichedPlan));

        assertThatThrownBy(() -> PlanningRun.Snapshot.fromCandidateSet(candidateSet))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("candidate plans must stay deterministic without recommendationReason");
    }

    private static PlanningRun run(
            PlanningRun.Status status, long workflowVersion, long rowVersion,
            Optional<OffsetDateTime> completedAt) {
        return run(status, workflowVersion, rowVersion, completedAt,
                Optional.empty(), Optional.empty());
    }

    private static PlanningRun run(
            PlanningRun.Status status, long workflowVersion, long rowVersion,
            Optional<OffsetDateTime> completedAt,
            Optional<UUID> learningTaskId, Optional<PlanningRun.FallbackReason> fallbackReason) {
        return new PlanningRun(
                UUID.randomUUID(), USER_ID, PROFILE_ID, JOB_ID, status,
                workflowVersion, rowVersion, CREATED_AT, completedAt,
                learningTaskId, fallbackReason);
    }

    private static PlanningCandidateSet candidateSet(UUID profileId, int count) {
        return new PlanningCandidateSet(
                IntStream.range(0, count)
                        .mapToObj(index -> deterministicPlan(profileId, index))
                        .toList());
    }

    private static LearningTaskPlan deterministicPlan(UUID profileId, int sequence) {
        return new LearningTaskPlan(
                profileId,
                identity(sequence),
                "en",
                "zh",
                MaterialDifficulty.FOUNDATION,
                7,
                "Ordering breakfast at a café",
                "Ask the staff a follow-up question about today's specials",
                LearningTaskPlan.TaskType.TEXT_PRACTICE,
                LearningTaskPlan.PlanningReason.DETERMINISTIC_BUILT_IN_FALLBACK);
    }

    private static MaterialIdentity identity(int sequence) {
        return new MaterialIdentity(
                "builtin:text-practice/material-" + sequence, "2026.03.1");
    }
}
