package com.freshcutz.service;

import com.freshcutz.dto.AdminStoreResponse;
import com.freshcutz.dto.StoreScheduleDto;
import com.freshcutz.dto.StoreStatusResponse;
import com.freshcutz.entity.StoreSettings;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.StoreSettingsRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Whether the shop is currently accepting orders — either by the manual
 * switch, or automatically by an opening-hours schedule.
 *
 * <p>Built to cost almost nothing:
 * <ul>
 *   <li>The settings row is cached in memory as an immutable snapshot, so the
 *       hot paths (every order, every storefront connection) never touch the
 *       database. It is replaced on every write and re-read at most every
 *       5 minutes.</li>
 *   <li>"Open right now?" is pure arithmetic on that snapshot and the clock —
 *       nothing is polled and no job writes to the database at opening time.</li>
 *   <li>One single-thread timer sleeps until the next open/close moment, then
 *       pushes the new status over the existing SSE stream, so storefronts and
 *       the admin page flip within a second. It is re-armed after every change
 *       and capped at 6 hours so it can never drift far.</li>
 * </ul>
 * If the server was asleep (free hosting) at an open/close moment nothing is
 * lost: the answer is computed from the clock on the next request.
 */
@Service
public class StoreSettingsService {

    private static final Logger log = LoggerFactory.getLogger(StoreSettingsService.class);

    private static final Long SETTINGS_ID = 1L;
    private static final long CACHE_MS = 5 * 60_000L;
    private static final long MAX_SLEEP_MS = 6 * 3_600_000L;
    private static final String DEFAULT_OPEN = "09:00";
    private static final String DEFAULT_CLOSE = "21:00";
    private static final String DEFAULT_ZONE = "Asia/Kolkata";
    private static final int ALL_DAYS = 0b1111111;
    private static final List<DateTimeFormatter> TIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("H:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("H:mm:ss", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h:mm:ss a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH));
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.ENGLISH);

    /** Immutable copy of the settings row, with the text fields already parsed. */
    private record Snapshot(boolean online, boolean auto, LocalTime open, LocalTime close,
                            int days, ZoneId zone, Long overrideUntil) {}

    private final StoreSettingsRepository repository;
    private final SseBroadcastService broadcastService;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "store-schedule");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pending; // guarded by this
    private volatile Snapshot snapshot;
    private volatile long loadedAt;
    private volatile Boolean lastBroadcast;

    public StoreSettingsService(StoreSettingsRepository repository, SseBroadcastService broadcastService) {
        this.repository = repository;
        this.broadcastService = broadcastService;
    }

    // ---- reads (hot path: no database) ---------------------------------------

    /** True when customers can place orders right now. */
    public boolean isOnline() {
        return effective(snapshot(), Instant.now());
    }

    public AdminStoreResponse adminView() {
        Snapshot s = snapshot();
        Instant now = Instant.now();
        boolean online = effective(s, now);
        ZonedDateTime next = s.auto() ? nextChange(s, now) : null;
        return AdminStoreResponse.builder()
                .online(online)
                .manualOverride(overrideActive(s, now))
                .schedule(toDto(s))
                .nextChangeAt(next == null ? null : next.toInstant().toEpochMilli())
                .nextChangeOpens(next != null && scheduleOpen(s, next.toInstant()))
                .nextChangeText(next == null ? null : LABEL.format(next))
                .build();
    }

    // ---- writes ---------------------------------------------------------------

    /** The topbar toggle. With the schedule on, flipping it against the schedule
     * holds until the schedule's next open/close moment, then the schedule resumes. */
    public synchronized AdminStoreResponse setOnline(boolean wanted) {
        Snapshot s = snapshot();
        StoreSettings row = getOrCreate();
        Instant now = Instant.now();
        row.setOnline(wanted);
        if (s.auto() && wanted != scheduleOpen(s, now)) {
            ZonedDateTime next = nextChange(s, now);
            row.setOverrideUntil(next == null ? Long.MAX_VALUE : next.toInstant().toEpochMilli());
        } else {
            row.setOverrideUntil(null);
        }
        persist(row);
        return adminView();
    }

    /** "Back to schedule": drops a manual override right away. */
    public synchronized AdminStoreResponse followSchedule() {
        StoreSettings row = getOrCreate();
        row.setOverrideUntil(null);
        persist(row);
        return adminView();
    }

    public synchronized AdminStoreResponse updateSchedule(StoreScheduleDto dto) {
        if (dto == null) throw new BadRequestException("Schedule is missing");
        LocalTime open = parseTime(dto.getOpenTime(), "opening");
        LocalTime close = parseTime(dto.getCloseTime(), "closing");
        if (open.equals(close)) throw new BadRequestException("Opening and closing time can't be the same");
        int mask = 0;
        if (dto.getDays() != null) {
            for (Integer d : dto.getDays()) {
                if (d == null || d < 1 || d > 7) throw new BadRequestException("Days must be 1 (Monday) to 7 (Sunday)");
                mask |= 1 << (d - 1);
            }
        }
        if (dto.isAutoEnabled() && mask == 0) throw new BadRequestException("Choose at least one day");
        if (mask == 0) mask = ALL_DAYS;
        String zone = dto.getTimezone() == null || dto.getTimezone().isBlank() ? DEFAULT_ZONE : dto.getTimezone().trim();
        try {
            ZoneId.of(zone);
        } catch (DateTimeException e) {
            throw new BadRequestException("Unknown time zone: " + zone);
        }

        Snapshot before = snapshot();
        Instant now = Instant.now();
        StoreSettings row = getOrCreate();
        // Switching the schedule off keeps the store exactly as customers see it now.
        if (before.auto() && !dto.isAutoEnabled()) row.setOnline(effective(before, now));
        row.setAutoEnabled(dto.isAutoEnabled());
        row.setOpenTime(open.toString());
        row.setCloseTime(close.toString());
        row.setOpenDays(mask);
        row.setTimezone(zone);
        row.setOverrideUntil(null); // a new schedule applies straight away
        persist(row);
        return adminView();
    }

    // ---- schedule maths -------------------------------------------------------

    private static boolean overrideActive(Snapshot s, Instant now) {
        return s.auto() && s.overrideUntil() != null && now.toEpochMilli() < s.overrideUntil();
    }

    private static boolean effective(Snapshot s, Instant now) {
        if (!s.auto()) return s.online();
        if (overrideActive(s, now)) return s.online();
        return scheduleOpen(s, now);
    }

    private static boolean dayAllowed(Snapshot s, LocalDate date) {
        return ((s.days() >> (date.getDayOfWeek().getValue() - 1)) & 1) == 1;
    }

    /** Pure schedule answer, ignoring any manual override. A window belongs to
     * the day it starts on, so a 6 PM - 2 AM window started Friday runs into Saturday. */
    private static boolean scheduleOpen(Snapshot s, Instant at) {
        ZonedDateTime z = at.atZone(s.zone());
        boolean overnight = s.close().isBefore(s.open());
        for (int back = overnight ? 1 : 0; back >= 0; back--) {
            LocalDate d = z.toLocalDate().minusDays(back);
            if (!dayAllowed(s, d)) continue;
            ZonedDateTime start = ZonedDateTime.of(d, s.open(), s.zone());
            ZonedDateTime end = ZonedDateTime.of(overnight ? d.plusDays(1) : d, s.close(), s.zone());
            if (!z.isBefore(start) && z.isBefore(end)) return true;
        }
        return false;
    }

    /** The first moment after {@code now} when the schedule's answer flips. */
    private static ZonedDateTime nextChange(Snapshot s, Instant now) {
        boolean current = scheduleOpen(s, now);
        ZonedDateTime base = now.atZone(s.zone());
        boolean overnight = s.close().isBefore(s.open());
        TreeSet<ZonedDateTime> edges = new TreeSet<>(Comparator.comparing(ZonedDateTime::toInstant));
        for (int i = -1; i <= 8; i++) {
            LocalDate d = base.toLocalDate().plusDays(i);
            if (!dayAllowed(s, d)) continue;
            edges.add(ZonedDateTime.of(d, s.open(), s.zone()));
            edges.add(ZonedDateTime.of(overnight ? d.plusDays(1) : d, s.close(), s.zone()));
        }
        for (ZonedDateTime edge : edges) {
            if (edge.toInstant().isAfter(now) && scheduleOpen(s, edge.toInstant()) != current) return edge;
        }
        return null;
    }

    // ---- cache + persistence --------------------------------------------------

    private Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s == null || System.currentTimeMillis() - loadedAt > CACHE_MS) {
            synchronized (this) {
                s = snapshot;
                if (s == null || System.currentTimeMillis() - loadedAt > CACHE_MS) {
                    s = toSnapshot(getOrCreate());
                    snapshot = s;
                    loadedAt = System.currentTimeMillis();
                }
            }
        }
        return s;
    }

    private StoreSettings getOrCreate() {
        return repository.findById(SETTINGS_ID).orElseGet(() -> {
            StoreSettings settings = new StoreSettings();
            settings.setId(SETTINGS_ID);
            settings.setOnline(true);
            return repository.save(settings);
        });
    }

    /** Saves, refreshes the cache, tells every open page, and re-arms the timer. */
    private void persist(StoreSettings row) {
        StoreSettings saved = repository.save(row);
        snapshot = toSnapshot(saved);
        loadedAt = System.currentTimeMillis();
        publishIfChanged();
        arm();
    }

    private static Snapshot toSnapshot(StoreSettings r) {
        LocalTime open = LocalTime.parse(r.getOpenTime() == null ? DEFAULT_OPEN : r.getOpenTime());
        LocalTime close = LocalTime.parse(r.getCloseTime() == null ? DEFAULT_CLOSE : r.getCloseTime());
        ZoneId zone;
        try {
            zone = ZoneId.of(r.getTimezone() == null ? DEFAULT_ZONE : r.getTimezone());
        } catch (DateTimeException e) {
            zone = ZoneId.of(DEFAULT_ZONE);
        }
        boolean auto = Boolean.TRUE.equals(r.getAutoEnabled()) && !open.equals(close);
        return new Snapshot(r.isOnline(), auto, open, close,
                r.getOpenDays() == null || r.getOpenDays() == 0 ? ALL_DAYS : r.getOpenDays(), zone, r.getOverrideUntil());
    }

    private static StoreScheduleDto toDto(Snapshot s) {
        List<Integer> days = new ArrayList<>();
        for (int i = 0; i < 7; i++) if (((s.days() >> i) & 1) == 1) days.add(i + 1);
        return StoreScheduleDto.builder()
                .autoEnabled(s.auto())
                .openTime(s.open().toString())
                .closeTime(s.close().toString())
                .days(days)
                .timezone(s.zone().getId())
                .build();
    }

    /** Accepts "09:00", "9:00", "09:00:00", "9:00 AM" and "09:00:00 pm" — seconds are ignored. */
    private static LocalTime parseTime(String text, String what) {
        String t = text == null ? "" : text.trim().toUpperCase(Locale.ENGLISH).replaceAll("\\s+", " ");
        for (DateTimeFormatter f : TIME_FORMATS) {
            try {
                return LocalTime.parse(t, f).withSecond(0).withNano(0);
            } catch (Exception ignored) {
                // try the next format
            }
        }
        throw new BadRequestException("Enter the " + what + " time like 09:00 or 9:00 AM");
    }

    // ---- live push + timer ----------------------------------------------------

    /** Tells every open storefront/admin page, but only when the answer really changed. */
    private void publishIfChanged() {
        boolean now = effective(snapshot, Instant.now());
        Boolean last = lastBroadcast;
        if (last == null || last != now) {
            lastBroadcast = now;
            broadcastService.broadcastStatus(StoreStatusResponse.builder().online(now).build());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            lastBroadcast = effective(snapshot(), Instant.now());
            arm();
        } catch (Exception e) {
            log.warn("Store schedule timer could not start: {}", e.toString());
        }
    }

    /** Sleeps until the next moment the answer can change (schedule edge or end of an override). */
    private synchronized void arm() {
        if (pending != null) pending.cancel(false);
        pending = null;
        Snapshot s = snapshot;
        if (s == null || !s.auto()) return;
        Instant now = Instant.now();
        long wake = Long.MAX_VALUE;
        ZonedDateTime next = nextChange(s, now);
        if (next != null) wake = next.toInstant().toEpochMilli();
        if (overrideActive(s, now) && s.overrideUntil() < wake) wake = s.overrideUntil();
        long delay = wake == Long.MAX_VALUE ? MAX_SLEEP_MS : Math.min(MAX_SLEEP_MS, wake - now.toEpochMilli() + 1000);
        pending = timer.schedule(this::tick, Math.max(1000, delay), TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            snapshot(); // picks up changes made straight in the database, too
            publishIfChanged();
        } catch (Exception e) {
            log.warn("Store schedule tick failed: {}", e.toString());
        } finally {
            arm();
        }
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }
}
