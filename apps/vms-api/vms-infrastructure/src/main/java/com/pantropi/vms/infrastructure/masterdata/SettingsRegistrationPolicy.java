package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.SettingsStore;
import com.pantropi.vms.application.visitor.port.RegistrationPolicy;

/**
 * The pre-registration rules, read from {@code vms.system_settings} (US-08.1.1 AC-5).
 *
 * <p>In the master-data context because settings are its data, and behind the visitor context's own
 * narrow port so that context depends on the one rule it needs rather than on the whole
 * configuration surface.
 *
 * <p>A missing or unparseable value falls back to the catalogue default rather than failing the
 * registration. The grace period is a guard against a mistyped date; a configuration problem should
 * not stop a receptionist admitting somebody, and the value it falls back to is the one the
 * catalogue already documents as correct.
 */
public final class SettingsRegistrationPolicy implements RegistrationPolicy {

    static final String KEY = "pre_registration.past_grace_minutes";
    static final int FALLBACK_MINUTES = 60;

    private final SettingsStore settings;

    public SettingsRegistrationPolicy(SettingsStore settings) {
        this.settings = settings;
    }

    @Override
    public int pastAppointmentGraceMinutes() {
        return settings.find(KEY)
                .map(setting -> parse(setting.value()))
                .orElse(FALLBACK_MINUTES);
    }

    private static int parse(String value) {
        try {
            int minutes = Integer.parseInt(value.trim());
            // A negative grace would mean "the appointment must start in the future", which is a
            // different rule from the one this setting names. Treated as unset.
            return minutes < 0 ? FALLBACK_MINUTES : minutes;
        } catch (NumberFormatException e) {
            return FALLBACK_MINUTES;
        }
    }
}
