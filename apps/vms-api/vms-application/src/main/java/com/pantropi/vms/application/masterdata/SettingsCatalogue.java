package com.pantropi.vms.application.masterdata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The reviewed catalogue of permitted system settings (US-04.8.1, T-04.8.1.1) — FR-SET-01.
 *
 * <p><strong>Keys are a fixed, reviewed list, not free-form</strong> (AC-5). A setting is a piece of
 * behaviour someone decided to make configurable; if any string could be written, a typo would
 * create a row that looks like a setting, is never read by anything, and quietly does nothing. That
 * failure is hard to notice and worse than an outright error, so an unknown key is refused.
 *
 * <p>The catalogue must correspond <em>exactly</em> to what the baseline seed puts in
 * {@code vms.system_settings}, in both directions. A test asserts this: a key seeded but not
 * catalogued would be unreadable through the API, and a key catalogued but not seeded would read as
 * its default while appearing to be stored.
 *
 * <p>Pure Java: no framework, no I/O.
 */
public final class SettingsCatalogue {

    /** How a value is interpreted. The stored jsonb must match, or the read fails loudly. */
    public enum Type { INTEGER, BOOLEAN, STRING }

    /**
     * @param defaultValue what the setting reads as when the row is absent — documented, never
     *                     silently null
     * @param secret       when true the value is never disclosed through the API or written to the
     *                     audit trail (US-04.8.2 AC-2). <strong>No catalogued key is secret today,
     *                     and a test asserts it</strong> — a real credential belongs in the F-05.2
     *                     secrets mechanism, not in a table an administrator can read over HTTP.
     *                     The marker exists so that if a setting ever legitimately holds something
     *                     sensitive, the redaction path is already built and tested rather than
     *                     retrofitted under pressure.
     */
    public record Entry(String key, Type type, String defaultValue, String description,
                        boolean secret) {}

    /** The settings this application understands. Ordered for a stable listing. */
    private static final List<Entry> ENTRIES = List.of(
            new Entry("default_pass_valid_hours", Type.INTEGER, "12",
                    "Fallback validity window when a pass type is not specified", false),
            new Entry("notification.email.enabled", Type.BOOLEAN, "true",
                    "Master switch for email notifications", false),
            new Entry("notification.whatsapp.enabled", Type.BOOLEAN, "false",
                    "WhatsApp disabled until Business API approval (TODO-05)", false),
            new Entry("acs.retry.max_attempts", Type.INTEGER, "5",
                    "Max outbound ACS retry attempts before dead-letter", false),
            new Entry("pre_registration.past_grace_minutes", Type.INTEGER, "60",
                    "How far into the past a pre-registered appointment may start before it is "
                            + "treated as a mistyped date (US-08.1.1 AC-5)", false));

    /** What a redacted value reads as. Fixed, so it can never be mistaken for a real value. */
    public static final String REDACTED = "********";

    /**
     * The value as it may be disclosed — through the API, or into the audit trail (US-04.8.2 AC-2).
     *
     * <p>Every path that renders a value goes through here rather than each deciding for itself.
     * Redaction that has to be remembered at each call site is redaction that will eventually be
     * forgotten at one of them, and the one that forgets is the leak.
     */
    public static String disclose(Entry entry, String value) {
        return entry.secret() ? REDACTED : value;
    }

    private static final Map<String, Entry> BY_KEY = index();

    private SettingsCatalogue() {
    }

    private static Map<String, Entry> index() {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry e : ENTRIES) {
            map.put(e.key(), e);
        }
        return map;
    }

    public static List<Entry> all() {
        return ENTRIES;
    }

    public static Optional<Entry> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(BY_KEY.get(key));
    }

    /**
     * @throws UnknownSetting if the key is not catalogued — the message names the key, which is safe
     *                        because reaching this point already required an authenticated principal
     *                        holding {@code settings.manage}
     */
    public static Entry require(String key) {
        return find(key).orElseThrow(() -> new UnknownSetting(key));
    }

    /**
     * Check that a proposed value is well formed for the key's declared type, returning the
     * canonical text to store.
     *
     * @throws InvalidSettingValue if the value does not parse as the declared type
     */
    public static String normalise(Entry entry, String value) {
        if (value == null) {
            throw new InvalidSettingValue(entry.key(), "a value is required");
        }
        String trimmed = value.trim();
        return switch (entry.type()) {
            case INTEGER -> {
                try {
                    yield Long.toString(Long.parseLong(trimmed));
                } catch (NumberFormatException e) {
                    throw new InvalidSettingValue(entry.key(), "expected a whole number");
                }
            }
            case BOOLEAN -> {
                String lower = trimmed.toLowerCase(Locale.ROOT);
                if (!lower.equals("true") && !lower.equals("false")) {
                    throw new InvalidSettingValue(entry.key(), "expected true or false");
                }
                yield lower;
            }
            case STRING -> trimmed;
        };
    }

    public static final class UnknownSetting extends RuntimeException {
        public final String key;

        public UnknownSetting(String key) {
            super("Unknown setting key: " + key);
            this.key = key;
        }
    }

    public static final class InvalidSettingValue extends RuntimeException {
        public final String key;

        public InvalidSettingValue(String key, String reason) {
            super("Invalid value for " + key + ": " + reason);
            this.key = key;
        }
    }
}
