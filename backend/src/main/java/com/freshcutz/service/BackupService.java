package com.freshcutz.service;

import com.freshcutz.entity.BackupState;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.BackupStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sends an Excel backup of the shop's data to the owner's e-mail through Resend.
 *
 * <p>Designed to add (almost) no load:
 * <ul>
 *   <li><b>Off by default.</b> Nothing runs until RESEND_API_KEY and BACKUP_EMAIL_TO are set;
 *       automatic sending also needs BACKUP_INTERVAL_HOURS &gt; 0.</li>
 *   <li><b>Idle checks never touch the database.</b> A tiny timer wakes every 10 minutes and
 *       only compares two numbers in memory. The database is read (one row) once after the server
 *       starts, and then only when a backup is actually due.</li>
 *   <li><b>"Catch up" is automatic.</b> Render's free server sleeps when nobody visits and starts
 *       again on the next visit (admin or customer) — on every start the check runs about a
 *       minute later, and sends the backup if one is due. When the database was awake anyway
 *       (the server's own start-up work uses it), this costs no extra database time.</li>
 *   <li><b>Light on memory/CPU.</b> One low-priority background thread; the data is streamed
 *       table by table straight into the zip file; photos and the logo (large binary columns)
 *       are never even read from the database.</li>
 *   <li><b>Failures back off.</b> If Resend or the database is unreachable, the next automatic
 *       attempt waits 30 minutes (manual button presses are never blocked).</li>
 * </ul>
 */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy hh:mm a", Locale.ENGLISH).withZone(SHOP_ZONE);
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter FILE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm", Locale.ENGLISH).withZone(SHOP_ZONE);

    private static final long RETRY_AFTER_FAILURE_MS = 30L * 60_000L;
    private static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024; // Resend's limit is 40 MB incl. base64
    private static final long MAX_BATCH_BYTES = 15L * 1024 * 1024;       // files per e-mail (before base64)
    private static final int MAX_PARTS = 10;                               // most files in one catch-up

    /** {database table, sheet name, newest-first?, date filter used when the admin limits the period (or null)}
     *  — in the order the sheets appear. Tables without a filter are always included in full. */
    private static final String WHERE_ORDERS = "created_at >= ? and created_at < ?";
    private static final String WHERE_ITEMS = "order_id in (select id from orders where created_at >= ? and created_at < ?)";
    private static final String WHERE_SALES = "sale_date >= ? and sale_date < ?";
    private static final Object[][] TABLES = {
            {"orders", "Orders", true, WHERE_ORDERS},
            {"order_items", "Order items", true, WHERE_ITEMS},
            {"customers", "Customers", false, null},
            {"products", "Products", false, null},
            {"product_sales_stats", "Daily sales", false, WHERE_SALES},
            {"site_customization", "Site design", false, null},
            {"site_template", "Saved designs", false, null},
            {"store_settings", "Store settings", false, null},
    };

    private final DataSource dataSource;
    private final BackupStateRepository stateRepository;
    private final String apiKey;
    private final String to;
    private final String from;
    private final long envIntervalHours; // from BACKUP_INTERVAL_HOURS; the admin's saved choice (below) wins over it
    private final String resendUrl;

    private volatile HttpClient httpClient; // built only when the first e-mail is sent (idle cost: nothing)
    private final AtomicBoolean running = new AtomicBoolean(false);
    // The worker thread exists only while a backup runs; after 20 s idle it ends and frees its memory.
    private final ExecutorService worker = newWorker();

    private static ExecutorService newWorker() {
        ThreadPoolExecutor ex = new ThreadPoolExecutor(1, 1, 20, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "backup-worker");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        ex.allowCoreThreadTimeOut(true);
        return ex;
    }

    private HttpClient http() {
        HttpClient c = httpClient;
        if (c == null) {
            synchronized (this) {
                c = httpClient;
                if (c == null) {
                    c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                    httpClient = c;
                }
            }
        }
        return c;
    }

    // In-memory copy of the single "last backup" row — see ensureStateLoaded().
    private volatile boolean stateLoaded = false;
    private volatile long lastSuccessAt = 0L;    // last backup of any kind (shown to the admin)
    private volatile long lastScheduledAt = 0L;  // last AUTOMATIC backup: drives the schedule and the "nothing missed" window
    private volatile String lastError = null;
    private volatile long retryNotBefore = 0L;
    // The admin's choices from the Customize tab (null = not chosen yet, follow BACKUP_INTERVAL_HOURS).
    private volatile Boolean autoEnabledSetting = null;
    private volatile Integer intervalHoursSetting = null;
    private volatile Integer rangeDaysSetting = null; // null/0 = everything
    private final Object stateLock = new Object();

    public BackupService(DataSource dataSource,
                         BackupStateRepository stateRepository,
                         @Value("${freshcutz.backup.resend-api-key:}") String apiKey,
                         @Value("${freshcutz.backup.to:}") String to,
                         @Value("${freshcutz.backup.from:FreshCutz Backup <onboarding@resend.dev>}") String from,
                         @Value("${freshcutz.backup.interval-hours:0}") long intervalHours,
                         @Value("${freshcutz.backup.resend-url:https://api.resend.com/emails}") String resendUrl) {
        this.dataSource = dataSource;
        this.stateRepository = stateRepository;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.to = to == null ? "" : to.trim();
        this.from = (from == null || from.isBlank()) ? "FreshCutz Backup <onboarding@resend.dev>" : from.trim();
        this.resendUrl = resendUrl;
        this.envIntervalHours = Math.max(0, intervalHours);
        if (isConfigured()) {
            log.info("Backup e-mail: ready (default: {})",
                    this.envIntervalHours > 0 ? "automatic every " + this.envIntervalHours + " hour(s)" : "button only until switched on in Customize");
        }
    }

    // ------------------------------------------------------------------
    // Automatic sending
    // ------------------------------------------------------------------

    /** First run 60 s after start-up (the catch-up check), then every 10 minutes. The
     *  quick path is two comparisons in memory — no database, no thread. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 600_000)
    public void tick() {
        if (!isConfigured()) return;
        long now = System.currentTimeMillis();
        if (now < retryNotBefore) return;
        if (stateLoaded) {
            if (!autoEnabled()) return;                                  // switched off — nothing to do
            if (now - lastScheduledAt < effectiveIntervalMs()) return;   // not due — nothing to do
        }
        if (running.get()) return;
        worker.execute(() -> {
            try {
                runIfDue();
            } catch (Throwable t) {
                log.warn("Backup check failed: {}", t.toString());
            }
        });
    }

    private void runIfDue() {
        if (!running.compareAndSet(false, true)) return;
        try {
            try {
                ensureStateLoaded();
            } catch (Exception e) {
                // database not reachable right now — try again later, don't hammer it
                retryNotBefore = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS;
                log.warn("Backup: could not read backup state ({}); will retry later", e.toString());
                return;
            }
            if (!autoEnabled()) return;
            if (System.currentTimeMillis() - lastScheduledAt < effectiveIntervalMs()) return;
            try {
                doBackup("Scheduled", true);
            } catch (Exception e) {
                lastError = shorten(e.getMessage());
                retryNotBefore = System.currentTimeMillis() + RETRY_AFTER_FAILURE_MS;
                log.warn("Backup e-mail failed: {}", e.toString());
            }
        } finally {
            running.set(false);
        }
    }

    // ------------------------------------------------------------------
    // Admin button
    // ------------------------------------------------------------------

    /** "Email backup now": builds and sends right away; returns what happened. */
    public Map<String, Object> sendNow() {
        if (!isConfigured()) {
            throw new BadRequestException("Backup e-mail isn't set up yet. On Render, add RESEND_API_KEY and BACKUP_EMAIL_TO, then redeploy.");
        }
        if (!running.compareAndSet(false, true)) {
            throw new BadRequestException("A backup is already being sent — give it a minute.");
        }
        try {
            ensureStateLoaded();
            Map<String, Object> result = doBackup("Manual (admin button)", false);
            lastError = null;
            return result;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            lastError = shorten(e.getMessage());
            log.warn("Manual backup failed: {}", e.toString());
            throw new BadRequestException("Backup could not be sent: " + shorten(e.getMessage()));
        } finally {
            running.set(false);
        }
    }

    /** For the admin screen. Reads the database only if the "last sent" row hasn't been loaded yet. */
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean configured = isConfigured();
        m.put("configured", configured);
        m.put("to", mask(to));
        m.put("running", running.get());
        if (configured) {
            try {
                ensureStateLoaded();
            } catch (Exception e) {
                m.put("lastError", "Could not read backup status: " + shorten(e.getMessage()));
            }
        }
        m.put("autoEnabled", autoEnabled());
        m.put("intervalHours", effectiveHours());
        m.put("rangeDays", rangeDays());
        if (stateLoaded && lastSuccessAt > 0) {
            m.put("lastSuccessAt", lastSuccessAt);
            m.put("lastSuccessText", DATE_FMT.format(Instant.ofEpochMilli(lastSuccessAt)) + " IST");
        }
        if (configured && stateLoaded && autoEnabled()) {
            long next = lastScheduledAt == 0 ? System.currentTimeMillis()
                    : Math.max(lastScheduledAt + effectiveIntervalMs(), retryNotBefore);
            m.put("nextAt", next);
            m.put("nextText", DATE_FMT.format(Instant.ofEpochMilli(next)) + " IST");
        }
        if (lastError != null) m.put("lastError", lastError);
        return m;
    }

    /** Saves the admin's choice from the Customize tab: automatic sending on/off, how often, and how many
     *  days of orders to include (0 = everything). Any argument may be null (= leave as is). */
    public Map<String, Object> updateSettings(Boolean enabled, Integer hours, Integer rangeDays) {
        if (!isConfigured()) {
            throw new BadRequestException("Backup e-mail isn't set up yet. On Render, add RESEND_API_KEY and BACKUP_EMAIL_TO, then redeploy.");
        }
        if (hours != null && (hours < 1 || hours > 720)) {
            throw new BadRequestException("Choose a backup interval between 1 hour and 30 days.");
        }
        if (rangeDays != null && (rangeDays < -1 || rangeDays > 3650)) {
            throw new BadRequestException("Choose how many days of data to include (0 = everything).");
        }
        ensureStateLoaded();
        synchronized (stateLock) {
            boolean wasOn = autoEnabled();
            if (enabled != null) autoEnabledSetting = enabled;
            if (hours != null) intervalHoursSetting = hours;
            if (rangeDays != null) rangeDaysSetting = rangeDays;
            if (autoEnabled() && !wasOn) {
                // Just switched on: the first automatic backup is one interval from now (it does not try to
                // "catch up" on the time it was off). If a backup ran very recently, keep that as the start.
                long nowMs = System.currentTimeMillis();
                if (lastScheduledAt <= 0 || nowMs - lastScheduledAt >= effectiveIntervalMs()) lastScheduledAt = nowMs;
            }
            retryNotBefore = 0L;
            BackupState st = stateRepository.findById(1L).orElseGet(() -> {
                BackupState s = new BackupState();
                s.setId(1L);
                return s;
            });
            st.setAutoEnabled(autoEnabledSetting);
            st.setIntervalHours(intervalHoursSetting);
            st.setRangeDays(rangeDaysSetting);
            if (lastScheduledAt > 0) st.setLastScheduledAt(lastScheduledAt);
            stateRepository.save(st);
        }
        return status();
    }

    /** 0 = everything, 1 = today, -1 = yesterday only, N = today plus the N-1 days before it.
     *  Until the admin chooses, it is Today. */
    private int rangeDays() {
        Integer d = rangeDaysSetting;
        return d == null ? 1 : d;
    }

    /** The period of orders, order items and daily sales one file covers: from (included) to (not included). */
    private static final class Window {
        final Instant from;
        final Instant to;
        final LocalDate startDay;  // first calendar day touched (Indian time) — for the per-day sales sheet
        final LocalDate endDay;    // first calendar day NOT touched
        final boolean exact;       // true = exact times (automatic backups); false = whole calendar days (the button)
        String text;

        Window(Instant from, Instant to, boolean exact) {
            this.from = from;
            this.to = to;
            this.exact = exact;
            this.startDay = LocalDate.ofInstant(from, SHOP_ZONE);
            this.endDay = LocalDate.ofInstant(to.minusMillis(1), SHOP_ZONE).plusDays(1);
        }

        static Window days(LocalDate first, LocalDate endExclusive) {
            return new Window(first.atStartOfDay(SHOP_ZONE).toInstant(), endExclusive.atStartOfDay(SHOP_ZONE).toInstant(), false);
        }

        Instant start() { return from; }
        Instant end() { return to; }
    }

    /** One Excel file of a backup: the period it covers (null window = everything) and its file name. */
    private static final class Part {
        Window window;
        String fileName;
        String partNote; // "Part 2 of 3" for catch-up files, else null
    }

    /** What a backup consists of, and — for automatic ones — the moment up to which data is now covered. */
    private static final class Plan {
        final List<Part> parts = new ArrayList<>();
        long cutoff = 0L; // automatic only: becomes the start of the next period
    }

    private static String span(Window w) {
        long days = ChronoUnit.DAYS.between(w.startDay, w.endDay);
        LocalDate last = w.endDay.minusDays(1);
        return DATE_ONLY.format(w.startDay) + (days == 1 ? "" : " to " + DATE_ONLY.format(last) + " (" + days + " days)");
    }

    private static String length(long ms) {
        long hours = Math.max(1, Math.round(ms / 3_600_000.0));
        if (hours % 24 == 0) {
            long d = hours / 24;
            return d + (d == 1 ? " day" : " days");
        }
        return hours + (hours == 1 ? " hour" : " hours");
    }

    /**
     * Works out which file(s) a backup consists of.
     *
     * <p><b>Manual button:</b> exactly one file for the "Data to include" period (today counts, so it is
     * "up to now"), or everything.
     *
     * <p><b>Automatic backup:</b> "Data to include" plays no part. Each file covers exactly one send interval,
     * and the periods follow each other with no gap and no overlap: "every 10 days" is a file with 10 days of
     * data, "every 6 hours" a file with 6 hours. If the server was down or asleep and several periods were
     * missed, the next backup carries one file per missed period, all in one e-mail (never more than
     * {@value #MAX_PARTS} files — the periods are joined if there would be more — and never further back than
     * a year). The periods sit on a fixed grid (previous end + interval), so being a few minutes late never
     * makes the schedule drift; whatever happened after the last period ends goes into the next one.
     */
    private Plan plan(Instant now, boolean scheduled) {
        Plan plan = new Plan();
        String stamp = "FreshCutz-backup-" + FILE_FMT.format(now) + ".xlsx";
        if (scheduled) {
            long nowMs = now.toEpochMilli();
            long iv = effectiveIntervalMs();
            long prev = lastScheduledAt > 0 ? lastScheduledAt : nowMs - iv;
            long floor = nowMs - 366L * 86_400_000L;
            if (prev < floor) prev = floor;
            long k = Math.max(1, (nowMs - prev) / iv);                  // whole periods that have passed
            long mult = (k + MAX_PARTS - 1) / MAX_PARTS;                // periods per file (1 unless a huge gap)
            long count = (k + mult - 1) / mult;
            plan.cutoff = prev + k * iv;
            long cursor = prev;
            for (int i = 1; i <= count; i++) {
                long chunkEnd = Math.min(cursor + mult * iv, plan.cutoff);
                Window w = new Window(Instant.ofEpochMilli(cursor), Instant.ofEpochMilli(chunkEnd), true);
                String range = DATE_FMT.format(w.from) + " to " + DATE_FMT.format(w.to) + " (" + length(chunkEnd - cursor) + ")";
                w.text = (count > 1 ? "Catch-up part " + i + " of " + count + " — " : "") + range;
                Part p = new Part();
                p.window = w;
                p.fileName = "FreshCutz-backup-" + FILE_FMT.format(w.from) + "_to_" + FILE_FMT.format(w.to) + ".xlsx";
                p.partNote = count > 1 ? "Part " + i + " of " + count : null;
                plan.parts.add(p);
                cursor = chunkEnd;
            }
            return plan;
        }

        int d = rangeDays();
        Part p = new Part();
        p.fileName = stamp;
        if (d != 0) {
            LocalDate today = LocalDate.ofInstant(now, SHOP_ZONE);
            Window w;
            String label;
            if (d < 0) {
                w = Window.days(today.minusDays(1), today);
                label = "Yesterday";
            } else {
                w = Window.days(today.minusDays(d - 1L), today.plusDays(1));
                label = d == 1 ? "Today" : "Last " + d + " days";
            }
            w.text = label + ": " + span(w);
            p.window = w;
        }
        plan.parts.add(p);
        return plan;
    }

    private boolean autoEnabled() {
        Boolean b = autoEnabledSetting;
        return b != null ? b : envIntervalHours > 0;
    }

    private long effectiveHours() {
        Integer h = intervalHoursSetting;
        if (h != null && h > 0) return h;
        return envIntervalHours > 0 ? envIntervalHours : 240; // default: every 10 days
    }

    private long effectiveIntervalMs() {
        return effectiveHours() * 3_600_000L;
    }

    // ------------------------------------------------------------------
    // The backup itself
    // ------------------------------------------------------------------

    /** A byte buffer that can be handed to the HTTP client without making another copy. */
    private static final class RawBytes extends ByteArrayOutputStream {
        RawBytes(int size) {
            super(size);
        }
        byte[] buffer() {
            return buf;
        }
        public int size() {
            return count;
        }
    }

    /** An Excel file ready to be attached, with what it contains (for the e-mail text). */
    private static final class Attach {
        final String name;
        byte[] bytes;
        final String range;
        final Map<String, Integer> counts;
        void release() {
            bytes = new byte[0];
        }
        Attach(String name, byte[] bytes, String range, Map<String, Integer> counts) {
            this.name = name;
            this.bytes = bytes;
            this.range = range;
            this.counts = counts;
        }
    }

    private Map<String, Object> doBackup(String reason, boolean scheduled) throws Exception {
        long started = System.currentTimeMillis();
        Instant now = Instant.ofEpochMilli(started);

        // Usually one file; after a long gap, one per missed period. Files go out together in one
        // e-mail (several e-mails only if they would be too big for one).
        Plan plan = plan(now, scheduled);
        List<Part> parts = plan.parts;
        List<Attach> batch = new ArrayList<>();
        long batchBytes = 0, totalBytes = 0;
        int emails = 0;
        // ONE database connection for all files of this run, and each table's column list is looked up once.
        Map<String, TableInfo> schema = new HashMap<>();
        Connection conn = dataSource.getConnection();
        boolean oldAutoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false); // needed so the driver streams rows instead of loading everything
            conn.setReadOnly(true);
        for (Part part : parts) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
            Map<String, Integer> counts = buildWorkbook(conn, schema, bos, now, reason, part.window, part.partNote);
            byte[] xlsx = bos.toByteArray();
            if (xlsx.length > MAX_ATTACHMENT_BYTES) {
                throw new IllegalStateException("The backup file is " + (xlsx.length / 1024 / 1024) + " MB, too large to e-mail. Choose a shorter period in Data to include.");
            }
            if (!batch.isEmpty() && batchBytes + xlsx.length > MAX_BATCH_BYTES) {
                sendViaResend(batch, now, emails++);
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(new Attach(part.fileName, xlsx, part.window == null ? "Everything" : part.window.text, counts));
            batchBytes += xlsx.length;
            totalBytes += xlsx.length;
        }
        } finally {
            // Give the connection back to the pool as soon as the data is read — before the slow e-mail upload.
            closeQuietly(conn, oldAutoCommit);
        }
        if (!batch.isEmpty()) sendViaResend(batch, now, emails++);

        // Everything went out. (If an e-mail in the middle failed, nothing is marked as done, so the next
        // attempt redoes the whole catch-up — at worst a repeated file, never a missing one.)
        long finished = System.currentTimeMillis();
        lastSuccessAt = finished;
        if (scheduled) lastScheduledAt = plan.cutoff;
        lastError = null;
        retryNotBefore = 0L;
        stateLoaded = true;
        final long sizeBytes = totalBytes;
        try {
            synchronized (stateLock) {
                BackupState st = stateRepository.findById(1L).orElseGet(() -> {
                    BackupState s = new BackupState();
                    s.setId(1L);
                    return s;
                });
                st.setLastSuccessAt(finished);
                if (scheduled) st.setLastScheduledAt(plan.cutoff);
                st.setLastSizeBytes(sizeBytes);
                st.setLastNote(shorten("Sent to " + mask(to) + " (" + reason + ", " + parts.size() + " file(s))"));
                stateRepository.save(st);
            }
        } catch (Exception e) {
            // The e-mail DID go out. Keep the in-memory time so we don't resend in a loop.
            log.warn("Backup was e-mailed but the 'last sent' row could not be saved: {}", e.toString());
        }
        log.info("Backup e-mailed to {} ({} file(s), {} KB, {} ms, {})", mask(to), parts.size(), totalBytes / 1024, finished - started, reason);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("message", "Backup sent to " + mask(to) + " (" + (parts.size() > 1 ? parts.size() + " files, " : "") + (totalBytes / 1024)
                + " KB). It can take a minute to arrive — check Spam too.");
        result.put("sizeKb", totalBytes / 1024);
        result.put("files", parts.size());
        result.put("lastSuccessText", DATE_FMT.format(Instant.ofEpochMilli(finished)) + " IST");
        return result;
    }

    /** Reads the one-row state once; afterwards it lives in memory. */
    private void ensureStateLoaded() {
        if (stateLoaded) return;
        BackupState st = stateRepository.findById(1L).orElse(null);
        lastSuccessAt = (st == null || st.getLastSuccessAt() == null) ? 0L : st.getLastSuccessAt();
        lastScheduledAt = (st == null || st.getLastScheduledAt() == null) ? 0L : st.getLastScheduledAt();
        autoEnabledSetting = st == null ? null : st.getAutoEnabled();
        intervalHoursSetting = st == null ? null : st.getIntervalHours();
        rangeDaysSetting = st == null ? null : st.getRangeDays();
        stateLoaded = true;
    }

    /** Streams every table into a zip/xlsx. Binary columns (product photos, the logo) are never read. */
    private Map<String, Integer> buildWorkbook(Connection conn, Map<String, TableInfo> schema, ByteArrayOutputStream out,
                                               Instant now, String reason, Window window, String partNote) throws Exception {
        Map<String, Integer> counts = new LinkedHashMap<>();
        try (MiniXlsxWriter xlsx = new MiniXlsxWriter(out)) {
            for (Object[] t : TABLES) {
                String table = (String) t[0];
                String sheet = (String) t[1];
                boolean newestFirst = (Boolean) t[2];
                String where = window != null ? (String) t[3] : null;
                TableInfo info = schema.computeIfAbsent(table, k -> readTableInfo(conn, k));
                if (info == null || info.cols.isEmpty()) continue;
                counts.put(sheet, dumpTable(conn, info, xlsx, table, sheet, newestFirst, where, window));
            }

            // Summary goes first in the workbook (but is written last, since it needs the counts).
            MiniXlsxWriter.Sheet s = xlsx.startSheet("Summary", List.of("What", "Details"), true);
            s.row("Backup created", DATE_FMT.format(now) + " IST");
            s.row("How it was triggered", reason);
            s.row("Sent to", to);
            s.row("Data included", window == null ? "Everything" : window.text);
            if (partNote != null) s.row("This file", partNote + " — each part covers a different set of days; together they cover everything since the previous automatic backup, with no gaps.");
            if (window != null) {
                s.row("Always in full", "Customers, products, site design and store settings.");
                if (window.exact) s.row("Daily sales", "That sheet is kept per whole day, so its first and last day can reach a little outside the period (and the same day can show up in two backups in a row).");
            }
            s.row("", "");
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                s.row(e.getKey() + " (rows)", e.getValue());
            }
            s.row("", "");
            s.row("Not included", "Product photos and the shop logo (stored as images — they stay safe in the database).");
            s.row("Not included", "Phone/browser notification sign-ups, and the admin password.");
            s.row("Times", "All dates and times are Indian time (IST).");
            s.end();
        }
        return counts;
    }

    /** What we need to know about one table; looked up once per backup run, not once per file. */
    private static final class TableInfo {
        final List<String> cols = new ArrayList<>();
        final List<Integer> types = new ArrayList<>();
        final List<String> headers = new ArrayList<>();
    }

    /** Null if the table does not exist. Binary columns (photos, logo) are left out. */
    private static TableInfo readTableInfo(Connection conn, String table) {
        try {
            if (!tableExists(conn, table)) return null;
            TableInfo info = new TableInfo();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("select * from " + table + " where 1=0")) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    int type = md.getColumnType(i);
                    if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB) continue;
                    String name = md.getColumnName(i);
                    info.cols.add("\"" + name.replace("\"", "\"\"") + "\"");
                    info.types.add(type);
                    info.headers.add(pretty(name));
                }
            }
            return info;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the structure of table " + table + ": " + e.getMessage(), e);
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        DatabaseMetaData md = conn.getMetaData();
        for (String name : new String[]{table, table.toUpperCase(Locale.ROOT)}) {
            try (ResultSet rs = md.getTables(null, null, name, new String[]{"TABLE"})) {
                if (rs.next()) return true;
            }
        }
        return false;
    }

    private static void closeQuietly(Connection conn, boolean oldAutoCommit) {
        try {
            conn.rollback(); // read-only: nothing to commit
            conn.setReadOnly(false);
            conn.setAutoCommit(oldAutoCommit);
        } catch (SQLException ignored) {
            // closed right below anyway
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // nothing more to do
        }
    }

    private int dumpTable(Connection conn, TableInfo info, MiniXlsxWriter xlsx, String table, String sheetName, boolean newestFirst,
                          String where, Window window) throws Exception {
        List<String> cols = info.cols;
        List<Integer> types = info.types;
        List<String> headers = info.headers;

        // 2) stream the rows (only the text/number columns)
        String sql = "select " + String.join(", ", cols) + " from " + table
                + (where != null ? " where " + where : "")
                + " order by 1" + (newestFirst ? " desc" : "");
        MiniXlsxWriter.Sheet sheet = xlsx.startSheet(sheetName, headers, false);
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setFetchSize(500);
            if (where != null) {
                if (where == WHERE_SALES) {
                    ps.setDate(1, java.sql.Date.valueOf(window.startDay));
                    ps.setDate(2, java.sql.Date.valueOf(window.endDay));
                } else {
                    ps.setTimestamp(1, Timestamp.from(window.start()), utc);
                    ps.setTimestamp(2, Timestamp.from(window.end()), utc);
                }
            }
            try (ResultSet rs = ps.executeQuery()) {
                Object[] row = new Object[cols.size()];
                while (rs.next()) {
                    for (int i = 0; i < row.length; i++) row[i] = cell(rs, i + 1, types.get(i), utc);
                    sheet.row(row.clone());
                }
            }
        }
        int n = sheet.rowCount();
        sheet.end();
        return n;
    }

    private static Object cell(ResultSet rs, int i, int type, Calendar utc) throws SQLException {
        switch (type) {
            case Types.TIMESTAMP:
            case Types.TIMESTAMP_WITH_TIMEZONE: {
                Timestamp ts = rs.getTimestamp(i, utc);
                return ts == null ? null : DATE_FMT.format(ts.toInstant());
            }
            case Types.BOOLEAN:
            case Types.BIT: {
                boolean b = rs.getBoolean(i);
                return rs.wasNull() ? null : (Object) b;
            }
            case Types.TINYINT:
            case Types.SMALLINT:
            case Types.INTEGER:
            case Types.BIGINT: {
                long v = rs.getLong(i);
                return rs.wasNull() ? null : (Object) v;
            }
            case Types.NUMERIC:
            case Types.DECIMAL:
                return rs.getBigDecimal(i);
            case Types.FLOAT:
            case Types.REAL:
            case Types.DOUBLE: {
                double d = rs.getDouble(i);
                return rs.wasNull() ? null : (Object) d;
            }
            default:
                return amPm(rs.getString(i)); // text, enums, dates ("2026-10-17"), json ...
        }
    }

    /** A time stored as text in 24-hour form ("21:00", e.g. the store's open/close time) -> "09:00 PM". */
    private static String amPm(String v) {
        if (v == null || v.length() != 5 || v.charAt(2) != ':') return v;
        try {
            int h = Integer.parseInt(v.substring(0, 2));
            int m = Integer.parseInt(v.substring(3));
            if (h < 0 || h > 23 || m < 0 || m > 59) return v;
            return String.format(Locale.ENGLISH, "%02d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "AM" : "PM");
        } catch (NumberFormatException e) {
            return v;
        }
    }

    /** order_code -> "Order code", id -> "ID". */
    private static String pretty(String column) {
        if (column.equalsIgnoreCase("id")) return "ID";
        String s = column.replace('_', ' ').trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return column;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------
    // Resend
    // ------------------------------------------------------------------

    private void sendViaResend(List<Attach> files, Instant now, int emailIndex) throws Exception {
        String when = DATE_FMT.format(now) + " IST";
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"font-family:Arial,sans-serif;font-size:14px;color:#222\">")
                .append("<p>Your FreshCutz backup is attached as ")
                .append(files.size() > 1 ? files.size() + " Excel files" : "an Excel file")
                .append(".</p><p><b>Created:</b> ").append(when).append("</p>");
        StringBuilder text = new StringBuilder("Your FreshCutz backup is attached as "
                + (files.size() > 1 ? files.size() + " Excel files" : "an Excel file") + ".\nCreated: " + when + "\n\n");
        for (Attach a : files) {
            StringBuilder line = new StringBuilder();
            for (Map.Entry<String, Integer> e : a.counts.entrySet()) {
                if (line.length() > 0) line.append(" · ");
                line.append(e.getKey()).append(' ').append(e.getValue());
            }
            html.append("<p><b>").append(htmlEscape(a.name)).append("</b><br>Data: ").append(htmlEscape(a.range))
                    .append("<br><span style=\"color:#555\">").append(htmlEscape(line.toString())).append("</span></p>");
            text.append(a.name).append("\n  Data: ").append(a.range).append("\n  ").append(line).append("\n\n");
        }
        html.append("<p style=\"color:#777\">Product photos and the logo are not in the files. ")
                .append("Open the Summary sheet first.</p></div>");

        // Build the request body straight into one byte buffer (attachments Base64-encoded on the fly), so a big
        // backup needs about 1.4x its size in memory instead of several copies of it.
        long total = 0;
        for (Attach a : files) total += a.bytes.length;
        StringBuilder head = new StringBuilder();
        head.append("{\"from\":").append(q(from));
        head.append(",\"to\":[");
        String[] recipients = to.split("[,;\\s]+");
        boolean firstRecipient = true;
        for (String r : recipients) {
            if (r.isBlank()) continue;
            if (!firstRecipient) head.append(',');
            head.append(q(r));
            firstRecipient = false;
        }
        head.append("],\"subject\":").append(q("FreshCutz backup - " + when + (files.size() > 1 ? " (" + files.size() + " files)" : "")));
        head.append(",\"html\":").append(q(html.toString()));
        head.append(",\"text\":").append(q(text.toString()));
        head.append(",\"attachments\":[");
        long estimate = head.length() * 2L + total * 4 / 3 + 512L * files.size() + 64;
        if (estimate > Integer.MAX_VALUE - 64) throw new IllegalStateException("The backup is too large to e-mail.");
        RawBytes body = new RawBytes((int) estimate);
        body.write(head.toString().getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < files.size(); i++) {
            body.write((i > 0 ? ",{\"filename\":" : "{\"filename\":").getBytes(StandardCharsets.UTF_8));
            body.write(q(files.get(i).name).getBytes(StandardCharsets.UTF_8));
            body.write(",\"content\":\"".getBytes(StandardCharsets.UTF_8));
            body.write(Base64.getEncoder().encode(files.get(i).bytes));
            body.write("\"}".getBytes(StandardCharsets.UTF_8));
            files.get(i).release(); // the raw file is no longer needed once it is encoded
        }
        body.write("]}".getBytes(StandardCharsets.UTF_8));

        HttpRequest req = HttpRequest.newBuilder(URI.create(resendUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("User-Agent", "freshcutz-backup/1.0")
                .header("Idempotency-Key", "freshcutz-backup-" + now.toEpochMilli() + "-" + emailIndex)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.buffer(), 0, body.size()))
                .build();
        HttpResponse<String> res = http().send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            String hint = "";
            if (res.statusCode() == 401 || res.statusCode() == 403) {
                hint = " (check RESEND_API_KEY; with onboarding@resend.dev you can only send to the e-mail you signed up to Resend with)";
            }
            throw new IllegalStateException("Resend answered " + res.statusCode() + ": " + shorten(res.body()) + hint);
        }
    }

    // ------------------------------------------------------------------
    // small helpers
    // ------------------------------------------------------------------

    private boolean isConfigured() {
        return !apiKey.isEmpty() && !to.isEmpty();
    }

    private static String mask(String email) {
        if (email == null || email.isBlank()) return "";
        String first = email.split("[,;\\s]+")[0];
        int at = first.indexOf('@');
        if (at <= 1) return first;
        return first.charAt(0) + "***" + first.substring(at);
    }

    private static String shorten(String s) {
        if (s == null) return "unknown error";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 280 ? s.substring(0, 280) + "..." : s;
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** JSON string literal. */
    private static String q(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
