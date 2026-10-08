package com.freshcutz.service;

import com.freshcutz.dto.CustomerAddressUpdateRequest;
import com.freshcutz.dto.CustomerLookupResponse;
import com.freshcutz.dto.CustomerOrderResponse;
import com.freshcutz.dto.CustomerSalesResponse;
import com.freshcutz.dto.CustomerSummaryResponse;
import com.freshcutz.dto.PagedCustomerSalesResponse;
import com.freshcutz.dto.PagedCustomersResponse;
import com.freshcutz.entity.Customer;
import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.repository.CustomerRepository;
import com.freshcutz.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/** Groups order history by phone number so the admin can see who's a
 * repeat customer, how many times they've ordered, and where to deliver —
 * see listCustomersPaged. Purely a read-side view over existing orders
 * (plus the standalone Customer profile table for the current address) —
 * doesn't change how orders themselves are stored. */
@Service
public class CustomerService {

    private static final ZoneId SHOP_ZONE = ZoneId.of("Asia/Kolkata");
    // Stand-ins for a missing bound on a half-open [from, to] range, so
    // the bounded ranking query never has to deal with NULL timestamps.
    private static final Instant FAR_FUTURE = Instant.parse("2200-01-01T00:00:00Z");

    private final OrderRepository orderRepository;
    private final CustomerRepository customerRepository;

    public CustomerService(OrderRepository orderRepository, CustomerRepository customerRepository) {
        this.orderRepository = orderRepository;
        this.customerRepository = customerRepository;
    }

    /** Paged + optionally search-filtered customer directory, backing the
     * Customers tab's infinite scroll — entirely one aggregate SQL query
     * (see CustomerRepository.findCustomerSummariesPage) instead of the
     * old approach of loading every order ever placed, with every item,
     * into the JVM and grouping/summing/sorting them there by hand on
     * every tab visit or search keystroke. `size` is clamped to a sane
     * range the same way OrderService.listPaged clamps its own. Sorted by
     * order count descending by default (repeat customers first) — see
     * the repository query's own comment for the full sort/tiebreak
     * order.
     *
     * cursorOrderCount/cursorLastOrderAt/cursorPhone are all null
     * together for the first page and all set together for every "load
     * more" past it — the order count + lastOrderAt + phone of the last
     * customer already loaded, driving keyset ("seek") pagination
     * instead of OFFSET, the same reasoning as the Orders tab's own
     * paging. */
    @Transactional(readOnly = true)
    public PagedCustomersResponse listCustomersPaged(String search, int size, Long cursorOrderCount,
                                                      Instant cursorLastOrderAt, String cursorPhone) {
        String q = (search == null || search.isBlank()) ? null : search.trim();
        int pageSize = Math.min(Math.max(size, 1), 100);

        List<Object[]> rows = customerRepository.findCustomerSummariesPage(
                q, cursorOrderCount, cursorLastOrderAt, cursorPhone, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<Object[]> pageRows = hasMore ? rows.subList(0, pageSize) : rows;

        List<CustomerSummaryResponse> customers = pageRows.stream().map(this::mapRow).collect(Collectors.toList());
        return PagedCustomersResponse.builder()
                .customers(customers)
                .hasMore(hasMore)
                .build();
    }

    /** Customers ranked by the total of their orders within an optional
     * [from, to] shop-local date range — highest first — backing the
     * Sales tab's "By Customer" view under its period dropdown and
     * All/New/Delivered/Cancelled filter (see SalesStatusFilter). from and
     * to are both null together for the All-time period and both set
     * together for every other one.
     *
     * Three query paths on purpose, so none ever does more work than it
     * has to:
     *  - Delivered-only, All-time: the customers table's own running
     *    total_spent/delivered_count (a plain indexed SELECT whose cost is
     *    the page size — see CustomerRepository's class comment).
     *  - Delivered-only, bounded period: aggregates just the delivered
     *    orders inside that window (an index range scan on
     *    idx_orders_status_delivered_at).
     *  - Anything including New or Cancelled orders (All, New,
     *    Cancelled): aggregated live from the orders table, each status
     *    dated by its own moment — the delivered-only rollups can't know
     *    about orders that aren't delivered. New orders are few by nature,
     *    so this stays cheap for every bounded period.
     * All three return the same row shape, so the frontend renders them
     * identically.
     *
     * cursorSpent/cursorPhone are both null for the first page and both
     * set (the last customer's own spent/phone) for every "load more"
     * after — keyset pagination, same shape as every other infinite
     * scroll here; phone is the tiebreaker since two customers can have
     * spent exactly the same. */
    @Transactional(readOnly = true)
    public PagedCustomerSalesResponse listTopCustomers(LocalDate from, LocalDate to, SalesStatusFilter filter,
                                                        String search, int size,
                                                        BigDecimal cursorSpent, String cursorPhone) {
        String q = (search == null || search.isBlank()) ? null : search.trim();
        int pageSize = Math.min(Math.max(size, 1), 100);
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(SHOP_ZONE).toInstant();
        Instant end = to == null ? FAR_FUTURE : to.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();

        List<Object[]> rows;
        if (filter == SalesStatusFilter.ALL && from == null && to == null) {
            // Heaviest case: delivered figures come from the customers
            // rollup, only New/Cancelled orders are read live.
            rows = customerRepository.findTopCustomersAllTimeAll(q, cursorSpent, cursorPhone, pageSize + 1);
        } else if (filter != SalesStatusFilter.DELIVERED) {
            rows = customerRepository.findTopCustomersMix(
                    filter.includeNew(), filter.includeDelivered(), filter.includeCancelled(),
                    start, end, q, cursorSpent, cursorPhone, pageSize + 1);
        } else if (from == null && to == null) {
            rows = customerRepository.findTopCustomersAllTime(q, cursorSpent, cursorPhone, pageSize + 1);
        } else {
            rows = customerRepository.findTopCustomersBetween(start, end, q, cursorSpent, cursorPhone, pageSize + 1);
        }
        boolean hasMore = rows.size() > pageSize;
        List<Object[]> pageRows = hasMore ? rows.subList(0, pageSize) : rows;

        List<CustomerSalesResponse> customers = pageRows.stream()
                .map(row -> CustomerSalesResponse.builder()
                        .phone((String) row[0])
                        .name((String) row[1])
                        .flatNo((String) row[2])
                        .addressLine((String) row[3])
                        .landmark((String) row[4])
                        .orders(toLong(row[5]).intValue())
                        .spent(toBigDecimal(row[6]))
                        .newOrders(toLong(row[7]).intValue())
                        .cancelledOrders(toLong(row[8]).intValue())
                        .newSpent(toBigDecimal(row[9]))
                        .cancelledSpent(toBigDecimal(row[10]))
                        .build())
                .collect(Collectors.toList());
        return PagedCustomerSalesResponse.builder()
                .customers(customers)
                .hasMore(hasMore)
                .build();
    }

    /** Every customer of the By Customer ranking for a period and status
     * box, not just one page — what "Export this period" writes on the
     * By Customer sub-tab. Walks the same keyset pages as the screen
     * (100 at a time) until the last one, so the numbers are exactly the
     * ones the list shows. */
    @Transactional(readOnly = true)
    public List<CustomerSalesResponse> listAllTopCustomers(LocalDate from, LocalDate to, SalesStatusFilter filter) {
        List<CustomerSalesResponse> all = new java.util.ArrayList<>();
        BigDecimal cursorSpent = null;
        String cursorPhone = null;
        while (true) {
            PagedCustomerSalesResponse page = listTopCustomers(from, to, filter, null, 100, cursorSpent, cursorPhone);
            all.addAll(page.getCustomers());
            if (!page.isHasMore() || page.getCustomers().isEmpty()) break;
            CustomerSalesResponse last = page.getCustomers().get(page.getCustomers().size() - 1);
            cursorSpent = last.getSpent();
            cursorPhone = last.getPhone();
        }
        return all;
    }

    /** Turns one row from findCustomerSummariesPage's native query into a
     * CustomerSummaryResponse — column order here must match that query's
     * SELECT list exactly (phone, name, flatNo, addressLine, landmark,
     * orderCount, totalSpent, lastOrderAt). Built by hand instead of
     * through some entity, since this never exists as one managed
     * Customer/Order entity — it's a plain aggregated/joined native query
     * row, the same reasoning as OrderService's own mapRow. */
    private CustomerSummaryResponse mapRow(Object[] row) {
        return CustomerSummaryResponse.builder()
                .phone((String) row[0])
                .name((String) row[1])
                .flatNo((String) row[2])
                .addressLine((String) row[3])
                .landmark((String) row[4])
                .orderCount(toLong(row[5]).intValue())
                .totalSpent(toBigDecimal(row[6]))
                .lastOrderAt(toInstant(row[7]))
                .build();
    }

    private Long toLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number) return ((Number) o).longValue();
        throw new IllegalStateException("Unexpected count type from native query: " + o.getClass());
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        if (o instanceof Number) return new BigDecimal(o.toString());
        throw new IllegalStateException("Unexpected amount type from native query: " + o.getClass());
    }

    /** A native query's timestamptz columns come back as java.sql.Timestamp
     * (sometimes java.util.Date), never java.time.Instant directly — same
     * reasoning as OrderService's own toInstant helper. */
    private Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant) return (Instant) o;
        if (o instanceof Timestamp) return ((Timestamp) o).toInstant();
        if (o instanceof Date) return ((Date) o).toInstant();
        throw new IllegalStateException("Unexpected timestamp type from native query: " + o.getClass());
    }

    /** One customer's full order history (items included) — fetched only
     * once the admin actually taps "View past orders" on their card (see
     * AdminCustomerController's GET /{phone}/orders), instead of every
     * customer's full history being sent down up front on every list
     * load whether anyone looks at it or not. Reuses the same
     * EntityGraph-fetch-joined repository method and DTO shape the
     * customer-facing "My Orders" screen already uses (ordersByPhone
     * below) — this is genuinely the same data, just reached from the
     * admin side instead of the storefront side. */
    @Transactional(readOnly = true)
    public List<CustomerOrderResponse> ordersForCustomer(String phone) {
        return ordersByPhone(phone);
    }

    /** The same, narrowed to a Sales-tab period and status filter — what a
     * By Customer card's "View past orders" shows, so it lists only the
     * orders that make up that card's order count and total (e.g. today's
     * Delivered ones), not the customer's whole history. Each order counts
     * on its own status's date (see OrderRepository.findByPhoneInPeriod).
     * Both bounds missing with the All filter is just the full history. */
    @Transactional(readOnly = true)
    public List<CustomerOrderResponse> ordersForCustomer(String phone, LocalDate from, LocalDate to,
                                                         SalesStatusFilter filter) {
        if (from == null && to == null && filter == SalesStatusFilter.ALL) {
            return ordersByPhone(phone);
        }
        String normalized = phone == null ? "" : phone.replaceAll("\\D", "");
        if (normalized.isBlank()) {
            return List.of();
        }
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(SHOP_ZONE).toInstant();
        Instant end = to == null ? FAR_FUTURE : to.plusDays(1).atStartOfDay(SHOP_ZONE).toInstant();
        return orderRepository.findByPhoneInPeriod(normalized, start, end).stream()
                .filter(o -> (o.getStatus() == OrderStatus.NEW && filter.includeNew())
                        || (o.getStatus() == OrderStatus.DELIVERED && filter.includeDelivered())
                        || (o.getStatus() == OrderStatus.CANCELLED && filter.includeCancelled()))
                .map(CustomerOrderResponse::from)
                .collect(Collectors.toList());
    }

    /** Public, unauthenticated lookup used at checkout to recognize a
     * returning customer by phone number and prefill their delivery
     * address — see CustomerLookupController. Strips everything but
     * digits before matching, since that's what the checkout form now
     * sends for both this lookup and the phone it saves on the order
     * itself (see Utils.normalizePhone on the frontend); a blank/garbage
     * phone just means "not found", never an error.
     *
     * NOTE: right now this hands back a saved address to anyone who
     * knows (or guesses) a phone number, with no verification — that's
     * deliberate for this first version, and is meant to be closed by
     * OTP verification on a future round, not by this method. */
    @Transactional(readOnly = true)
    public CustomerLookupResponse lookupByPhone(String phone) {
        String normalized = phone == null ? "" : phone.replaceAll("\\D", "");
        if (normalized.isBlank()) {
            return CustomerLookupResponse.builder().found(false).build();
        }

        return customerRepository.findById(normalized)
                .map(c -> CustomerLookupResponse.builder()
                        .found(true)
                        .name(c.getName())
                        .flatNo(c.getFlatNo())
                        .addressLine(c.getAddressLine())
                        .landmark(c.getLandmark())
                        .build())
                // No profile row yet (a customer who ordered before this table
                // existed) — fall back to their most recent order, same as
                // before. It gets a real profile row the moment they place
                // their next order (see upsertFromOrder) or edit their
                // address, so this fallback is only ever a one-time thing.
                .orElseGet(() -> orderRepository.findFirstByPhoneOrderByCreatedAtDesc(normalized)
                        .map(o -> CustomerLookupResponse.builder()
                                .found(true)
                                .name(o.getCustomerName())
                                .flatNo(o.getFlatNo())
                                .addressLine(o.getAddressLine())
                                .landmark(o.getLandmark())
                                .build())
                        .orElseGet(() -> CustomerLookupResponse.builder().found(false).build()));
    }

    /** Keeps the profile table in sync with whatever address a customer
     * most recently actually placed an order with — called once, right
     * after an order is saved (see OrderService.placeOrder). The order
     * itself is never touched again after this; only this profile row is
     * written to on future address changes.
     *
     * Also advances this phone's order_count and last_order_at by one
     * atomic UPSERT (CustomerRepository.upsertOnOrderPlaced) rather than
     * a plain JPA save() — a save() here would overwrite the whole row,
     * silently resetting order_count/total_spent back to whatever the
     * unset builder fields default to, which is exactly what those two
     * columns exist to avoid recomputing from scratch on every Customers
     * tab load. See that query's own comment for why an atomic UPSERT
     * instead of read-then-write. */
    @Transactional
    public void upsertFromOrder(Order order) {
        customerRepository.upsertOnOrderPlaced(
                order.getPhone(),
                order.getCustomerName(),
                order.getFlatNo(),
                order.getAddressLine(),
                order.getLandmark(),
                order.getCreatedAt());
    }

    /** Adds a just-delivered order's amount onto its customer's running
     * total — called from OrderService.markDelivered right after the
     * order itself is saved as Delivered. See
     * CustomerRepository.addToTotalSpent for why this is a direct atomic
     * UPDATE rather than loading the Customer row, mutating it, and
     * saving it back. */
    @Transactional
    public void recordOrderDelivered(Order order) {
        customerRepository.addToTotalSpent(order.getPhone(), order.getTotalAmount());
    }

    /** The inverse — called from OrderService.reopen only when the order
     * being reopened was Delivered (not Cancelled, which was never added
     * to the total to begin with), since it no longer counts as
     * delivered once it's back to New. */
    @Transactional
    public void recordOrderUndelivered(Order order) {
        customerRepository.subtractFromTotalSpent(order.getPhone(), order.getTotalAmount());
    }

    /** Full order history for the customer-facing "My Orders" screen — same
     * unauthenticated-by-phone pattern as lookupByPhone() above, so it
     * comes with the same caveat: knowing a phone number is all it takes
     * to see every order ever placed under it (items, amounts, delivery
     * notes included). Deliberately excludes customerName/phone from each
     * row (see CustomerOrderResponse) since the customer already knows
     * who they are. Also reused by ordersForCustomer() above for the
     * admin-side "View past orders" expand — it's the same data either
     * way, just reached from a different screen. */
    @Transactional(readOnly = true)
    public List<CustomerOrderResponse> ordersByPhone(String phone) {
        String normalized = phone == null ? "" : phone.replaceAll("\\D", "");
        if (normalized.isBlank()) {
            return List.of();
        }
        return orderRepository.findByPhoneOrderByCreatedAtDesc(normalized).stream()
                .map(CustomerOrderResponse::from)
                .collect(Collectors.toList());
    }

    /** Lets a customer fix their saved delivery address from the profile
     * screen without placing a new order. Writes to the standalone
     * Customer profile row only — deliberately never to any Order, so
     * editing your address here can't rewrite where a past order says it
     * was delivered. Returns the updated snapshot in the same shape
     * lookupByPhone uses, so the profile screen can just re-render
     * straight from the response. */
    @Transactional
    public CustomerLookupResponse updateAddress(CustomerAddressUpdateRequest request) {
        String normalized = request.getPhone() == null ? "" : request.getPhone().replaceAll("\\D", "");
        if (normalized.isBlank()) {
            throw new BadRequestException("Phone number is required");
        }
        if (request.getName() == null || request.getName().isBlank()) {
            throw new BadRequestException("Please enter your name");
        }
        if (request.getFlatNo() == null || request.getFlatNo().isBlank()) {
            throw new BadRequestException("Please enter your flat / house no.");
        }
        if (request.getAddressLine() == null || request.getAddressLine().isBlank()) {
            throw new BadRequestException("Please enter your address");
        }

        // Writes only to the profile table — never to any placed order.
        // An order is a permanent record of what was actually delivered
        // where (and under what name); changing your saved name/address
        // afterwards must not rewrite that history.
        //
        // save() here replaces the whole row, not just the fields set on
        // the builder below — so without first reading back whatever
        // order_count/total_spent/last_order_at this phone already has
        // (null for a phone with no row yet, which is fine: it means no
        // order has been placed under it to aggregate in the first
        // place), an address edit would silently wipe those three back
        // to null, forcing them to look recomputed from scratch next
        // time the Customers tab loads this phone — exactly what those
        // columns exist to avoid. See CustomerRepository.upsertOnOrderPlaced
        // for why order placement itself instead uses an atomic UPSERT
        // that can't have this problem.
        Customer existing = customerRepository.findById(normalized).orElse(null);
        Customer saved = customerRepository.save(Customer.builder()
                .phone(normalized)
                .name(request.getName().trim())
                .flatNo(request.getFlatNo().trim())
                .addressLine(request.getAddressLine().trim())
                .landmark(blankToNull(request.getLandmark()))
                .orderCount(existing != null ? existing.getOrderCount() : null)
                .totalSpent(existing != null ? existing.getTotalSpent() : null)
                .lastOrderAt(existing != null ? existing.getLastOrderAt() : null)
                .deliveredCount(existing != null ? existing.getDeliveredCount() : null)
                .build());

        return CustomerLookupResponse.builder()
                .found(true)
                .name(saved.getName())
                .flatNo(saved.getFlatNo())
                .addressLine(saved.getAddressLine())
                .landmark(saved.getLandmark())
                .build();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
