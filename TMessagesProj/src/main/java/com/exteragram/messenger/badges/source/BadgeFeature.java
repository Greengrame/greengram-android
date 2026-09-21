package com.exteragram.messenger.badges.source;

/** A configurable feature row shown in the badge details sheet. */
public final class BadgeFeature {
    public Boolean enabled;
    public Long icon_document_id;
    public String title;
    public String text;

    public boolean isEnabled() {
        return enabled == null || enabled;
    }
}
