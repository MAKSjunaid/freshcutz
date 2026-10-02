package com.freshcutz.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freshcutz.dto.OrderItemRequest;
import com.freshcutz.dto.OrderItemResponse;
import com.freshcutz.dto.OrderRequest;
import com.freshcutz.dto.OrderResponse;
import com.freshcutz.dto.PagedOrdersResponse;
import com.freshcutz.entity.*;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.exception.ResourceNotFoundException;
import com.freshcutz.repository.OrderRepository;
import com.freshcutz.util.OrderCodeGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final OrderCodeGenerator orderCodeGenerator;
    private final StoreSettingsService storeSettingsService;
    private final PushNotificationService pushNotificationService;
    private final CustomerService customerService;
    private final ObjectMapper objectMapper;

    public OrderService(OrderRepository orderRepository, ProductService productService,
                         OrderCodeGenerator orderCodeGenerator, StoreSettingsService storeSettingsService,
                         PushNotificationService pushNotificationService, CustomerService customerService,
                         ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.productService = productService;
        this.orderCodeGenerator = orderCodeGenerator;
        this.storeSettingsService = storeSettingsService;
        this.pushNotificationService = pushNotificationService;
        this.customerService = customerService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public OrderResponse placeOrder(OrderRequest request) {
        if (!storeSettingsService.isOnline()) {
            throw new BadRequestException("Store is currently offline and not accepting new orders. Please check back later.");
        }

        Order order = Order.builder()
                .orderCode(orderCodeGenerator.next())
                .customerName(request.getCustomerName().trim())
                .phone(request.getPhone().trim())
                .flatNo(request.getFlatNo().trim())
                .addressLine(request.getAddressLine().trim())
                .landmark(blankToNull(request.getLandmark()))
                .notes(blankToNull(request.getNotes()))
                .status(OrderStatus.NEW)
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemRequest : request.getItems()) {
            OrderItem item = buildItem(itemRequest);
            order.addItem(item);
            total = total.add(item.getLineTotal());
        }
        if (order.getItems().isEmpty()) {
            throw new BadRequestException("Cart is empty");
        }
        order.setTotalAmount(total);

        Order saved = orderRepository.save(order);
        // Keeps "My Account" / checkout's returning-customer prefill in sync
        // with the address just used to order — a separate profile row, so
        // nothing about this order itself is ever touched again after this.
        customerService.upsertFromOrder(saved);
        pushNotificationService.notifyNewOrder(saved);
        return OrderResponse.from(saved);
    }

    private OrderItem buildItem(OrderItemRequest itemRequest) {
        Product product = productService.getEntity(itemRequest.getProductId());
        if (!product.isEnabled()) {
            throw new BadRequestException(product.getName() + " is currently unavailable");
        }
        if (product.isOutOfStock()) {
            throw new BadRequestException(product.getName() + " is currently out of stock");
        }

        String variantKey = "variant2".equals(itemRequest.getVariant()) ? "variant2" : "variant1";
        String label;
        BigDecimal price;
        if ("variant2".equals(variantKey)) {
            if (product.getVariant2Price() == null) {
                throw new BadRequestException(product.getName() + " has no " + itemRequest.getVariant() + " option");
            }
            label = product.getVariant2Label();
            price = product.getVariant2Price();
        } else {
            label = product.getVariant1Label();
            price = product.getVariant1Price();
        }

        BigDecimal lineTotal = price.multiply(BigDecimal.valueOf(itemRequest.getQuantity()));

        return OrderItem.builder()
                .productId(product.getId())
                .productName(product.getName())
                .productImageUrl(product.getImageUrl())
                .variantLabel(label)
                .variant(variantKey)
                .unitPrice(price)
                .quantity(itemRequest.getQuantity())
                .lineTotal(lineTotal)
                .build();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listAll() {
        return orderRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }

    /** Raw entities (not the trimmed DTO) — used by the "export all" flow. */
    @Transactional(readOnly = true)
    public List<Order> listAllEntities() {
        return orderRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> listByStatus(OrderStatus status) {
        return orderRepository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(OrderResponse::from)
                .collect(Collectors.toList());
    }

    /** Paged + optionally search-filtered version of listAll/listByStatus
     * above, backing the Orders tab's infinite scroll — status == null
     * means the "All" tab, same convention as those two methods. `size`
     * is clamped to a sane range so a bad/huge value from a client can't
     * force one query to pull back the whole table anyway, which would
     * defeat the entire point of paging.
     *
     * cursorStatusUpdatedAt/cursorId are both null for the first page (the
     * Orders tab's refresh()) and both set for every "load more" past it
     * — they're the statusUpdatedAt+id of the last order already loaded,
     * driving keyset ("seek") pagination instead of OFFSET for every page
     * after the first (see OrderRepository.findOrdersWithItemsOneTrip).
     * Keyed on statusUpdatedAt rather than createdAt so paging stays
     * consistent with the Delivered/Cancelled/All tabs' sort order — see
     * Order.statusUpdatedAt's own comment for why that's the right field
     * to sort by. */
    @Transactional(readOnly = true)
    public PagedOrdersResponse listPaged(OrderStatus status, String search, int size,
                                          Instant cursorStatusUpdatedAt, Long cursorId) {
        String q = (search == null || search.isBlank()) ? null : search.trim();

        // NEW orders don't actually need pagination at all: this shop
        // gets roughly 50-60 a day and they're actively worked down to
        // Delivered/Cancelled, so the New tab never holds enough rows for
        // paging to matter — unlike All/Delivered/Cancelled, which only
        // ever grow. So instead of the 2-round-trip paged path below
        // (page query + items query), this fetches every NEW order in
        // ONE query — the same single EntityGraph fetch-join query
        // (findByStatusOrderByCreatedAtDesc) already used and proven
        // before pagination existed, which loads items in the same
        // round trip instead of a separate one. Search, when active, is
        // then just a plain in-memory filter over that (small) list
        // rather than a third repository method — cheap, since we're
        // talking about tens of rows, not thousands. hasMore is always
        // false here because everything is already returned on page 0;
        // the frontend's existing infinite-scroll code already does
        // nothing when hasMore is false, so this needs no frontend
        // change at all. totalElements is always the true NEW count
        // (ignoring search) — exactly what the Orders-tab badge needs,
        // matching the existing convention that the frontend only ever
        // reads totalElements while NOT searching (see admin-orders.js).
        if (status == OrderStatus.NEW) {
            List<Order> allNew = orderRepository.findByStatusOrderByCreatedAtDesc(OrderStatus.NEW);
            List<Order> filtered = (q == null) ? allNew : allNew.stream()
                    .filter(o -> matchesSearch(o, q))
                    .collect(Collectors.toList());
            List<OrderResponse> orders = filtered.stream().map(OrderResponse::from).collect(Collectors.toList());
            return PagedOrdersResponse.builder()
                    .orders(orders)
                    .totalElements(allNew.size())
                    .hasMore(false)
                    .build();
        }

        int pageSize = Math.min(Math.max(size, 1), 100);
        String statusParam = (status == null) ? null : status.name();

        // One database round trip for both the page of orders AND their
        // items (see OrderRepository.findOrdersWithItemsOneTrip) — asking
        // for pageSize + 1 rows, not pageSize, is how hasMore is worked
        // out below without a separate COUNT(*) query: if the extra row
        // comes back, there's more; either way it's trimmed off before
        // mapping to the response.
        //
        // TEMPORARY timing log — remove once we've confirmed where the
        // remaining time actually goes. Compares the DB call's own
        // duration against the full request, so the IntelliJ console
        // shows directly whether it's the query, or network/connection
        // overhead to Neon that nothing in this method can fix.
        long dbStart = System.currentTimeMillis();
        // The All tab (statusParam == null) uses a query with no status
        // condition at all, rather than this method's status-filtered
        // query called with a null status — see
        // OrderRepository.findAllOrdersWithItemsOneTrip's javadoc for why
        // that distinction is worth a second query method.
        List<Object[]> rows = (statusParam == null)
                ? orderRepository.findAllOrdersWithItemsOneTrip(q, cursorStatusUpdatedAt, cursorId, pageSize + 1)
                : orderRepository.findOrdersWithItemsOneTrip(statusParam, q, cursorStatusUpdatedAt, cursorId, pageSize + 1);
        long dbMs = System.currentTimeMillis() - dbStart;
        log.info("listPaged: status={} search={} cursor={} rows={} dbCallMs={}",
                statusParam, q != null, cursorStatusUpdatedAt != null, rows.size(), dbMs);
        boolean hasMore = rows.size() > pageSize;
        List<Object[]> pageRows = hasMore ? rows.subList(0, pageSize) : rows;

        List<OrderResponse> orders = pageRows.stream().map(this::mapRow).collect(Collectors.toList());

        // An exact total is only ever read by the frontend for the New
        // tab's badge (see admin-orders.js's refresh() /
        // AdminStats.setNewOrdersCount), which is handled entirely by the
        // early-return above — status == NEW never reaches here, so
        // nothing below needs a count: hasMore (from the extra-row trick
        // above) is all the frontend actually uses for these three tabs.
        return PagedOrdersResponse.builder()
                .orders(orders)
                .totalElements(0)
                .hasMore(hasMore)
                .build();
    }

    /** Turns one row from findOrdersWithItemsOneTrip's native query into
     * an OrderResponse — column order here must match the SELECT list
     * there exactly (id, orderCode, customerName, phone, flatNo,
     * addressLine, landmark, notes, status, totalAmount, createdAt,
     * deliveredAt, statusUpdatedAt, itemsJson). Built by hand instead of
     * through OrderResponse.from(Order) because this data never exists as
     * a managed Order entity — it comes back from a plain aggregated/
     * joined native query, not Hibernate loading an Order. */
    private OrderResponse mapRow(Object[] row) {
        return OrderResponse.builder()
                .id(toLong(row[0]))
                .orderCode((String) row[1])
                .customerName((String) row[2])
                .phone((String) row[3])
                .flatNo((String) row[4])
                .addressLine((String) row[5])
                .landmark((String) row[6])
                .notes((String) row[7])
                .status((String) row[8])
                .totalAmount(toBigDecimal(row[9]))
                .createdAt(toInstant(row[10]))
                .deliveredAt(toInstant(row[11]))
                .statusUpdatedAt(toInstant(row[12]))
                .items(parseItems((String) row[13]))
                .build();
    }

    /** Parses the items_json column (a JSON array built by Postgres's
     * jsonb_agg/jsonb_build_object in findOrdersWithItemsOneTrip) into
     * OrderItemResponses. Reads via Jackson's JsonNode tree rather than
     * deserializing straight into OrderItemResponse or a generic
     * Map<String,Object>: a plain ObjectMapper's untyped deserialization
     * turns JSON decimal numbers (unitPrice/lineTotal) into Double by
     * default, which risks tiny floating-point drift on money values —
     * JsonNode.decimalValue() instead gives the exact BigDecimal Postgres
     * sent, no precision lost either way. */
    private List<OrderItemResponse> parseItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            List<OrderItemResponse> items = new ArrayList<>();
            for (JsonNode item : array) {
                items.add(OrderItemResponse.builder()
                        .productId(longOrNull(item, "productId"))
                        .productName(textOrNull(item, "productName"))
                        .productImageUrl(textOrNull(item, "productImageUrl"))
                        .variantLabel(textOrNull(item, "variantLabel"))
                        .variant(textOrNull(item, "variant"))
                        .unitPrice(decimalOrNull(item, "unitPrice"))
                        .quantity(item.path("quantity").asInt())
                        .lineTotal(decimalOrNull(item, "lineTotal"))
                        .build());
            }
            return items;
        } catch (JsonProcessingException e) {
            // Would mean Postgres's own jsonb_agg output isn't valid
            // JSON, which shouldn't be possible — surfaced loudly rather
            // than silently dropping an order's items.
            throw new IllegalStateException("Could not parse order items JSON from the database", e);
        }
    }

    /** A JSON null (not just a missing field) makes plain .asText()
     * return the literal string "null" instead of Java null — a classic
     * Jackson gotcha — so every nullable field from parseItems above
     * goes through one of these three helpers instead of a bare
     * .asText()/.asLong()/.decimalValue() call. */
    private String textOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private Long longOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asLong();
    }

    private BigDecimal decimalOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.decimalValue();
    }

    /** The JDBC driver can hand back an id/count as Long, Integer, or
     * BigInteger depending on version/column type — coercing through
     * Number rather than a direct (Long) cast avoids a ClassCastException
     * if it's not exactly a Long. */
    private Long toLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        return Long.valueOf(o.toString());
    }

    private BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        return new BigDecimal(o.toString());
    }

    /** A native query's timestamptz columns come back as java.sql.Timestamp
     * (sometimes java.util.Date), never java.time.Instant directly —
     * unlike JPQL/entity-mapped fields, which Hibernate converts
     * automatically. OrderResponse.createdAt/deliveredAt are typed
     * Instant, so this converts explicitly rather than assuming the type. */
    private Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof Instant) return (Instant) o;
        if (o instanceof Timestamp) return ((Timestamp) o).toInstant();
        if (o instanceof Date) return ((Date) o).toInstant();
        throw new IllegalStateException("Unexpected timestamp type from native query: " + o.getClass());
    }

    /** Case-insensitive substring match against order code, phone, or
     * customer name — the same three fields and the same semantics as
     * the SQL-side search (LOWER(x) LIKE LOWER('%q%')), just done in
     * memory for the New tab's small, already-fully-loaded order list
     * (see listPaged above) instead of a separate database query. */
    private boolean matchesSearch(Order o, String q) {
        String needle = q.toLowerCase();
        return (o.getOrderCode() != null && o.getOrderCode().toLowerCase().contains(needle))
                || (o.getPhone() != null && o.getPhone().toLowerCase().contains(needle))
                || (o.getCustomerName() != null && o.getCustomerName().toLowerCase().contains(needle));
    }

        /** Full detail (address, notes, items) for exactly one order — backs
     * the Sales tab's on-tap detail fetch (AdminOrderController's
     * GET /{id}), so an admin opening one order's row costs one small
     * request, not the whole range's worth of full order data up front. */
    @Transactional(readOnly = true)
    public OrderResponse getById(Long id) {
        return OrderResponse.from(getEntity(id));
    }

    @Transactional
    public OrderResponse markDelivered(Long id) {
        Order order = getEntity(id);
        Instant now = Instant.now();
        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(now);
        order.setStatusUpdatedAt(now);
        Order saved = orderRepository.save(order);
        // Keeps the Customers tab's "total delivered" figure current
        // without it ever having to re-sum orders — see
        // CustomerService.recordOrderDelivered.
        customerService.recordOrderDelivered(saved);
        pushNotificationService.notifyOrderDelivered(saved);
        return OrderResponse.from(saved);
    }

    @Transactional
    public OrderResponse markCancelled(Long id) {
        Order order = getEntity(id);
        order.setStatus(OrderStatus.CANCELLED);
        order.setStatusUpdatedAt(Instant.now());
        return OrderResponse.from(orderRepository.save(order));
    }

    /** An order can only be reopened within 24 hours of being marked
     * Delivered/Cancelled — after that the admin's "Reopen as New" option
     * is gone (enforced here too, in case something still calls this
     * endpoint past the UI's own cutoff). Deliberately measured from
     * statusUpdatedAt (when it was actioned), not createdAt (when it was
     * originally placed): an order placed four days ago but only marked
     * delivered/cancelled just now is still fresh to reopen right now —
     * it's the action being undone that needs to be recent, not the
     * order's age. statusUpdatedAt defaults to createdAt for an order
     * that's never changed status (see Order.onCreate), so this is a
     * no-op change for any order still sitting in NEW. */
    private static final long REOPEN_WINDOW_HOURS = 24;

    @Transactional
    public OrderResponse reopen(Long id) {
        Order order = getEntity(id);
        Instant actionedAt = order.getStatusUpdatedAt() != null ? order.getStatusUpdatedAt() : order.getCreatedAt();
        Instant cutoff = actionedAt.plusSeconds(REOPEN_WINDOW_HOURS * 3600);
        if (Instant.now().isAfter(cutoff)) {
            throw new BadRequestException("This order was marked Delivered/Cancelled more than 24 hours ago and can no longer be reopened.");
        }
        // Captured before the status flip below: only an order that was
        // actually Delivered (not Cancelled, which was never added to the
        // customer's total to begin with) needs its amount backed out of
        // that running total now that it's no longer delivered.
        boolean wasDelivered = order.getStatus() == OrderStatus.DELIVERED;
        order.setStatus(OrderStatus.NEW);
        order.setDeliveredAt(null);
        order.setStatusUpdatedAt(Instant.now());
        Order saved = orderRepository.save(order);
        if (wasDelivered) {
            customerService.recordOrderUndelivered(saved);
        }
        return OrderResponse.from(saved);
    }

    private Order getEntity(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " not found"));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
