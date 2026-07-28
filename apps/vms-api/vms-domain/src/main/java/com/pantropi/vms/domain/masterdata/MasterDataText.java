package com.pantropi.vms.domain.masterdata;

import java.util.Locale;

/**
 * The {@code code} and {@code name} rules every coded master data entity shares (US-04.1.1,
 * T-04.1.1.1) — buildings, floors, tenants, receptions, visitor types, pass types.
 *
 * <p>Written once here rather than per entity. Six copies of "trim it, is it blank, is it too long"
 * is six chances for one of them to be subtly different, and the one that differs is the one that
 * lets a bad row in.
 *
 * <p><strong>Codes are upper-cased.</strong> They are identifiers people type into other systems and
 * quote to each other, and the database's unique constraint is case-sensitive — so without
 * normalising, {@code WGT} and {@code wgt} would be two different buildings that every human
 * involved would read as one. Names are left exactly as typed apart from trimming, because a name is
 * for people to read.
 *
 * <p>Pure Java: no framework, no I/O.
 */
public final class MasterDataText {

    /** Long enough for a meaningful mnemonic, short enough to stay a code rather than a sentence. */
    private static final int CODE_MAX = 32;
    private static final int NAME_MAX = 200;

    private MasterDataText() {
    }

    /**
     * @throws InvalidField if blank, too long, or containing anything but A-Z, 0-9, underscore and
     *                      hyphen — a code with a space or a slash breaks the moment it appears in a
     *                      URL path or a CSV import
     */
    public static String code(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new InvalidField("code", "is required");
        }
        if (value.length() > CODE_MAX) {
            throw new InvalidField("code", "must be at most " + CODE_MAX + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean allowed = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-';
            if (!allowed) {
                throw new InvalidField("code",
                        "may contain only letters, digits, underscore and hyphen");
            }
        }
        return value;
    }

    /** @throws InvalidField if blank or too long */
    public static String name(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            throw new InvalidField("name", "is required");
        }
        if (value.length() > NAME_MAX) {
            throw new InvalidField("name", "must be at most " + NAME_MAX + " characters");
        }
        return value;
    }

    /** Optional free text — trimmed, bounded, and empty becomes null so the column stays clean. */
    public static String optionalText(String field, String raw, int max) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > max) {
            throw new InvalidField(field, "must be at most " + max + " characters");
        }
        return value;
    }

    /** Names the offending field, so the API can say which one without the caller guessing. */
    public static final class InvalidField extends RuntimeException {
        public final String field;

        public InvalidField(String field, String problem) {
            super(field + " " + problem);
            this.field = field;
        }
    }
}
