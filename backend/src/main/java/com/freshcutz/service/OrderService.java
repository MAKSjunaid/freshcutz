package com.freshcutz.service;

import com.freshcutz.dto.OrderItemRequest;
import com.freshcutz.dto.OrderRequest;
import com.freshcutz.dto.OrderResponse;
import com.freshcutz.entity.*;
import com.freshcutz.exception.BadRequestException;
import com.freshcutz.exception.ResourceNotFoundException;
import com.freshcutz.repository.OrderRepository;
import com.freshcutz.util.OrderCodeGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final OrderCodeGenerator orderCodeGenerator;
    private final StoreSettingsService storeSettingsService;
    private final PushNotificationService pushNotificationService;

    public OrderService(OrderRepository orderRepository, ProductService productService,
                         OrderCodeGenerator orderCodeGenerator, StoreSettingsService storeSettingsService,
                         PushNotificationService pushNotificationService) {
        this.orderRepository = orderRepository;
        this.productService = productService;
        this.orderCodeGenerator = orderCodeGenerator;
        this.storeSettingsService = storeSettingsService;
        this.pushNotificationService = pushNotificationService;
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

        String label;
        BigDecimal price;
        if ("variant2".equals(itemRequest.getVariant())) {
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

    @Transactional
    public OrderResponse markDelivered(Long id) {
        Order order = getEntity(id);
        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(Instant.now());
        Order saved = orderRepository.save(order);
        pushNotificationService.notifyOrderDelivered(saved);
        return OrderResponse.from(saved);
    }

    @Transactional
    public OrderResponse markCancelled(Long id) {
        Order order = getEntity(id);
        order.setStatus(OrderStatus.CANCELLED);
        return OrderResponse.from(orderRepository.save(order));
    }

    /** An order can only be reopened within 24 hours of being placed — after
     * that the admin's "Reopen as New" option is gone (enforced here too, in
     * case something still calls this endpoint past the UI's own cutoff). */
    private static final long REOPEN_WINDOW_HOURS = 24;

    @Transactional
    public OrderResponse reopen(Long id) {
        Order order = getEntity(id);
        Instant cutoff = order.getCreatedAt().plusSeconds(REOPEN_WINDOW_HOURS * 3600);
        if (Instant.now().isAfter(cutoff)) {
            throw new BadRequestException("This order is more than 24 hours old and can no longer be reopened.");
        }
        order.setStatus(OrderStatus.NEW);
        order.setDeliveredAt(null);
        return OrderResponse.from(orderRepository.save(order));
    }

    private Order getEntity(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order " + id + " not found"));
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
