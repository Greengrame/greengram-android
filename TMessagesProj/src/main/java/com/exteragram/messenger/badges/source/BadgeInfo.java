package com.exteragram.messenger.badges.source;

import androidx.annotation.NonNull;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.api.model.ProfileStatus;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class BadgeInfo {

    private final BadgeDTO badge;
    private final ProfileStatus status;
    private final boolean canChangeBadge;
    private final boolean showBadgeButton;
    private final boolean forceMoreButton;
    private final String editorSecret;
    private final String badgeButtonText;
    private final Long infoIconDocumentId;
    private final String infoTitle;
    private final String infoMessage;
    private final String closeText;
    private final List<BadgeFeature> infoFeatures;

    public BadgeInfo(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge, boolean showBadgeButton) {
        this(badge, status, canChangeBadge, showBadgeButton, false, null, null, null, null, null, null, null);
    }

    public BadgeInfo(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge, boolean showBadgeButton,
                     boolean forceMoreButton, String badgeButtonText, Long infoIconDocumentId, String infoTitle,
                     String infoMessage, String closeText, List<BadgeFeature> infoFeatures) {
        this(badge, status, canChangeBadge, showBadgeButton, forceMoreButton, null, badgeButtonText, infoIconDocumentId,
                infoTitle, infoMessage, closeText, infoFeatures);
    }

    public BadgeInfo(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge, boolean showBadgeButton,
                     boolean forceMoreButton, String editorSecret, String badgeButtonText, Long infoIconDocumentId,
                     String infoTitle, String infoMessage, String closeText, List<BadgeFeature> infoFeatures) {
        this.badge = badge;
        this.status = status;
        this.canChangeBadge = canChangeBadge;
        this.showBadgeButton = showBadgeButton;
        this.forceMoreButton = forceMoreButton;
        this.editorSecret = editorSecret;
        this.badgeButtonText = badgeButtonText;
        this.infoIconDocumentId = infoIconDocumentId;
        this.infoTitle = infoTitle;
        this.infoMessage = infoMessage;
        this.closeText = closeText;
        this.infoFeatures = infoFeatures == null ? null : Collections.unmodifiableList(infoFeatures);
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

    public boolean getForceMoreButton() {
        return forceMoreButton;
    }

    public String getEditorSecret() {
        return editorSecret;
    }

    public String getBadgeButtonText() {
        return badgeButtonText;
    }

    public Long getInfoIconDocumentId() {
        return infoIconDocumentId;
    }

    public String getInfoTitle() {
        return infoTitle;
    }

    public String getInfoMessage() {
        return infoMessage;
    }

    public String getCloseText() {
        return closeText;
    }

    public List<BadgeFeature> getInfoFeatures() {
        return infoFeatures;
    }

    public BadgeInfo copy(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge, boolean showBadgeButton) {
        return new BadgeInfo(badge, status, canChangeBadge, showBadgeButton, forceMoreButton, editorSecret, badgeButtonText, infoIconDocumentId, infoTitle, infoMessage, closeText, infoFeatures);
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
                && forceMoreButton == info.forceMoreButton
                && Objects.equals(badge, info.badge)
                && status == info.status
                && Objects.equals(editorSecret, info.editorSecret)
                && Objects.equals(badgeButtonText, info.badgeButtonText)
                && Objects.equals(infoIconDocumentId, info.infoIconDocumentId)
                && Objects.equals(infoTitle, info.infoTitle)
                && Objects.equals(infoMessage, info.infoMessage)
                && Objects.equals(closeText, info.closeText)
                && Objects.equals(infoFeatures, info.infoFeatures);
    }

    @Override
    public int hashCode() {
        return Objects.hash(badge, status, canChangeBadge, showBadgeButton, forceMoreButton, editorSecret, badgeButtonText, infoIconDocumentId, infoTitle, infoMessage, closeText, infoFeatures);
    }

    @NonNull
    @Override
    public String toString() {
        return "BadgeInfo(badge=" + badge + ", status=" + status
                + ", canChangeBadge=" + canChangeBadge
                + ", showBadgeButton=" + showBadgeButton + ")";
    }
}
