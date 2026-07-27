package com.pantropi.vms.application.identity.usecase;

import com.pantropi.vms.application.identity.port.AuditTrail;
import com.pantropi.vms.application.identity.port.UserAdministrationStore;
import com.pantropi.vms.application.identity.port.UserAdministrationStore.NewUser;
import com.pantropi.vms.application.shared.port.TransactionRunner;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Bulk provisioning of reception accounts from a reviewed CSV (US-02.2.2, T-02.2.2.1/2).
 *
 * <p>Delivers import mechanics ONLY: it does not encode a per-floor account-count rule or a licence
 * ceiling, and never creates shared accounts — that rule is added once TODO-08 is dispositioned.
 * Passwords are never accepted through import; each created user gets an out-of-band activation
 * token (AC-4).
 *
 * <p>CSV columns (case-insensitive, order-independent): {@code username}, {@code email} (optional),
 * {@code fullName}, {@code roleCode}, {@code receptionId}. A {@code password}-like column rejects
 * the whole file.
 */
public final class UserImport {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,60}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> REQUIRED_COLUMNS =
            Set.of("username", "fullname", "rolecode", "receptionid");

    private final UserAdministrationStore store;
    private final AuditTrail audit;
    private final TransactionRunner tx;
    private final AccountActivation activation;

    public UserImport(UserAdministrationStore store, AuditTrail audit, TransactionRunner tx,
                      AccountActivation activation) {
        this.store = store;
        this.audit = audit;
        this.tx = tx;
        this.activation = activation;
    }

    // ---- T-02.2.2.1 dry-run preview (no writes) ----

    public Preview preview(String csv) {
        List<String[]> rows = parse(csv);          // throws on password column / malformed header
        Map<String, Integer> col = header(rows.get(0));
        List<RowOutcome> outcomes = new ArrayList<>();
        Set<String> seenInFile = new HashSet<>();

        for (int i = 1; i < rows.size(); i++) {
            outcomes.add(classify(rows.get(i), col, i + 1, seenInFile));
        }
        int creatable = (int) outcomes.stream().filter(o -> o.status() == Status.CREATABLE).count();
        int conflict = (int) outcomes.stream().filter(o -> o.status() == Status.CONFLICT).count();
        int rejected = (int) outcomes.stream().filter(o -> o.status() == Status.REJECTED).count();
        return new Preview(outcomes, creatable, conflict, rejected);
    }

    // ---- T-02.2.2.2 confirmed, transactional execution ----

    /**
     * @param confirmSkipConflicts required to proceed when the file contains conflicts (AC-3)
     * @throws ConfirmationRequired if conflicts exist and confirmation was not given
     */
    public Result execute(UUID actorId, String csv, String fileName, boolean confirmSkipConflicts) {
        Preview preview = preview(csv);
        if (preview.conflicts() > 0 && !confirmSkipConflicts) {
            throw new ConfirmationRequired(preview);
        }

        List<Created> created = tx.call(() -> {
            List<Created> made = new ArrayList<>();
            Map<String, Integer> col = header(parse(csv).get(0));
            List<String[]> rows = parse(csv);
            for (int i = 1; i < rows.size(); i++) {
                RowOutcome outcome = classify(rows.get(i), col, i + 1, new HashSet<>());
                if (outcome.status() != Status.CREATABLE) {
                    continue; // conflicts skipped (confirmed), rejects excluded
                }
                String[] r = rows.get(i);
                NewUser u = new NewUser(val(r, col, "username"), val(r, col, "email"),
                        val(r, col, "fullname"), val(r, col, "rolecode"),
                        UUID.fromString(val(r, col, "receptionid")), null);
                UUID id = store.insert(u, UserAdministration.ACTIVATION_PENDING);
                String token = activation.issueToken(id);              // out-of-band delivery
                audit.recordChange(actorId, "user.imported", "user", id.toString(), null,
                        "{\"username\":\"" + u.username() + "\",\"role\":\"" + u.roleCode() + "\"}");
                made.add(new Created(id, u.username(), token));
            }
            return made;
        });

        // Summary audit records the file identity by name and content hash — never the contents.
        audit.record(actorId, "user.import_completed", "import", sha256(csv),
                "file=" + fileName + " created=" + created.size()
                        + " skipped=" + preview.conflicts() + " rejected=" + preview.rejected());

        return new Result(created, preview.conflicts(), preview.rejected());
    }

    // ---- parsing & classification ----

    private RowOutcome classify(String[] r, Map<String, Integer> col, int line, Set<String> seenInFile) {
        String username = val(r, col, "username");
        String email = val(r, col, "email");
        String fullName = val(r, col, "fullname");
        String roleCode = val(r, col, "rolecode");
        String receptionId = val(r, col, "receptionid");

        if (username == null || !USERNAME.matcher(username).matches()) {
            return new RowOutcome(line, username, Status.REJECTED, "invalid username");
        }
        if (email != null && !EMAIL.matcher(email).matches()) {
            return new RowOutcome(line, username, Status.REJECTED, "invalid email");
        }
        if (fullName == null || fullName.isBlank()) {
            return new RowOutcome(line, username, Status.REJECTED, "missing fullName");
        }
        if (roleCode == null || store.roleIdByCode(roleCode).isEmpty()) {
            return new RowOutcome(line, username, Status.REJECTED, "unknown role");
        }
        UUID rec;
        try {
            rec = UUID.fromString(receptionId);
        } catch (RuntimeException e) {
            return new RowOutcome(line, username, Status.REJECTED, "missing or malformed receptionId");
        }
        if (!store.receptionActive(rec)) {
            return new RowOutcome(line, username, Status.REJECTED, "reception not found or inactive");
        }
        if (!seenInFile.add(username.toLowerCase(Locale.ROOT))) {
            return new RowOutcome(line, username, Status.REJECTED, "duplicate username within file");
        }
        if (store.usernameExists(username) || (email != null && store.emailExists(email))) {
            return new RowOutcome(line, username, Status.CONFLICT, "username or email already exists");
        }
        return new RowOutcome(line, username, Status.CREATABLE, null);
    }

    private List<String[]> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            throw new MalformedFile("empty file");
        }
        List<String[]> rows = new ArrayList<>();
        for (String line : csv.replace("\r\n", "\n").split("\n")) {
            if (!line.isBlank()) {
                rows.add(java.util.Arrays.stream(line.split(",", -1))
                        .map(String::trim).toArray(String[]::new));
            }
        }
        if (rows.isEmpty()) {
            throw new MalformedFile("no rows");
        }
        Map<String, Integer> col = header(rows.get(0));
        for (String name : col.keySet()) {
            if (name.contains("password") || name.contains("passwd") || name.contains("secret")) {
                throw new PasswordColumnRejected(name);
            }
        }
        if (!col.keySet().containsAll(REQUIRED_COLUMNS)) {
            throw new MalformedFile("missing required columns; need " + REQUIRED_COLUMNS);
        }
        return rows;
    }

    private static Map<String, Integer> header(String[] headerRow) {
        Map<String, Integer> col = new java.util.HashMap<>();
        for (int i = 0; i < headerRow.length; i++) {
            col.put(headerRow[i].toLowerCase(Locale.ROOT), i);
        }
        return col;
    }

    private static String val(String[] row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        if (i == null || i >= row.length) return null;
        String v = row[i];
        return v == null || v.isBlank() ? null : v;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    // ---- data shapes ----
    public enum Status { CREATABLE, CONFLICT, REJECTED }
    public record RowOutcome(int line, String username, Status status, String reason) {}
    public record Preview(List<RowOutcome> rows, int creatable, int conflicts, int rejected) {}
    public record Created(UUID id, String username, String activationToken) {}
    public record Result(List<Created> created, int skipped, int rejected) {}

    // ---- failures ----
    public static final class PasswordColumnRejected extends RuntimeException {
        public PasswordColumnRejected(String column) {
            super("Import rejected: a password/secret column ('" + column + "') is never accepted");
        }
    }
    public static final class MalformedFile extends RuntimeException {
        public MalformedFile(String why) { super("Malformed import file: " + why); }
    }
    public static final class ConfirmationRequired extends RuntimeException {
        public final transient Preview preview;
        public ConfirmationRequired(Preview preview) {
            super("Import contains conflicts; confirm to proceed with the remaining rows");
            this.preview = preview;
        }
    }
}
