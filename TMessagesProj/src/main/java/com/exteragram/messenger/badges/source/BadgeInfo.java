package com.exteragram.messenger.badges.source;

import androidx.annotation.NonNull;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.api.model.ProfileStatus;

import java.util.Objects;

public final class BadgeInfo {

    private final BadgeDTO badge;
    private final ProfileStatus status;
    private final boolean canChangeBadge;
    private final boolean showBadgeButton;

    public BadgeInfo(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge, boolean showBadgeButton) {
        this.badge = badge;
        this.status = status;
        this.canChangeBadge = canChangeBadge;
        this.showBadgeButton = showBadgeButton;
    }

    public BadgeDTO getBadge() {
        return badge;
    }

    public ProfileStatus getStatus() {
        return status;
    }

    public boolean getCanChangeBadge() {
        return canChangeBadge;
    }

    public boolean getShowBadgeButton() {
        return showBadgeButton;
    }

    public BadgeInfo copy(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge, boolean showBadgeButton) {
        return new BadgeInfo(badge, status, canChangeBadge, showBadgeButton);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BadgeInfo)) {
            return false;
        }
        BadgeInfo info = (BadgeInfo) other;
        return canChangeBadge == info.canChangeBadge
                && showBadgeButton == info.showBadgeButton
                && Objects.equals(badge, info.badge)
                && status == info.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(badge, status, canChangeBadge, showBadgeButton);
    }

    @NonNull
    @Override
    public String toString() {
        return "BadgeInfo(badge=" + badge + ", status=" + status
                + ", canChangeBadge=" + canChangeBadge
                + ", showBadgeButton=" + showBadgeButton + ")";
    }
}
