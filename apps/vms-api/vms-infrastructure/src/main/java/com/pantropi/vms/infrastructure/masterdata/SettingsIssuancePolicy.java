package com.pantropi.vms.infrastructure.masterdata;

import com.pantropi.vms.application.masterdata.port.SettingsStore;
import com.pantropi.vms.application.visitor.port.IssuancePolicy;

/**
 * Whether approval mints passes, read from {@code vms.system_settings} (US-09.1.2 AC-2).
 *
 * <p>In the master-data context because settings are its data, behind the visitor context's own
 * narrow port — the same arrangement as {@link SettingsRegistrationPolicy}, for the same reasons.
 *
 * <p>A missing or unparseable value falls back to the catalogue default. The backlog warns against
 * "failing open to automatic issuance", and the seed migration is what answers that: the row is
 * always present, so the fallback is a safety net rather than the normal path, and an administrator
 * who wants issuance off can see the setting and turn it off.
 */
public final class SettingsIssuancePolicy implements IssuancePolicy {

    static final String KEY = "credential.auto_issue_on_approval";
    static final boolean FALLBACK = true;

    private final SettingsStore settings;

    public SettingsIssuancePolicy(SettingsStore settings) {
        this.settings = settings;
    }

    @Override
    public boolean autoIssueOnApproval() {
        return settings.find(KEY).map(setting -> parse(setting.value())).orElse(FALLBACK);
    }

    private static boolean parse(String value) {
        String trimmed = value.trim();
        // Only the two spellings the catalogue's BOOLEAN type permits. Anything else is a
        // configuration problem, and guessing at it would be guessing about whether visitors get
        // passes, so it reads as the documented default instead.
        if ("true".equalsIgnoreCase(trimmed)) {
            return true;
        }
        return "false".equalsIgnoreCase(trimmed) ? false : FALLBACK;
    }
}
