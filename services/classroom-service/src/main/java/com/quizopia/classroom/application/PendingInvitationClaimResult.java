package com.quizopia.classroom.application;

/** Counts the local records affected by a pending-invitation claim. */
public record PendingInvitationClaimResult(int invitationsClaimed, int membershipsCreated) {
    public PendingInvitationClaimResult {
        if (invitationsClaimed < 0 || membershipsCreated < 0 || membershipsCreated > invitationsClaimed) {
            throw new IllegalArgumentException("Invalid pending-invitation claim counts");
        }
    }
}
