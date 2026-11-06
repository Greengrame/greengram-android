package org.telegram.greengram;

import static org.telegram.messenger.LocaleController.formatPluralStringComma;

import org.telegram.tgnet.tl.TL_stars;

// Greengram collections can be unlimited: the server sends availability_total = 0
// for them, so anything that divides by or prints the total has to check it.
public final class GiftCounts {

    private GiftCounts() {
    }

    public static boolean hasTotal(TL_stars.StarGift gift) {
        return gift != null && gift.availability_total > 0;
    }

    // "N/M issued", or "N issued" when there is no cap
    public static String issued(TL_stars.StarGift gift) {
        if (!hasTotal(gift)) {
            return formatPluralStringComma("Gift2QuantityIssued2", gift == null ? 0 : gift.availability_issued);
        }
        return formatPluralStringComma("Gift2QuantityIssued1", gift.availability_issued) + formatPluralStringComma("Gift2QuantityIssued2", gift.availability_total);
    }

    // availability row of a collectible: same as upstream, minus "None of 0 left"
    public static String uniqueAvailability(TL_stars.StarGift gift) {
        if (!hasTotal(gift)) {
            return issued(gift);
        }
        return gift.availability_remains <= 0 ? formatPluralStringComma("Gift2QuantityIssuedNone", gift.availability_total) : issued(gift);
    }
}
