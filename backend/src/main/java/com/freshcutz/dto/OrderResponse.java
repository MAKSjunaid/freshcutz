package com.freshcutz.dto;

import com.freshcutz.entity.Order;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
@AllArgsConstructor
public class OrderResponse {

    private Long id;
    private String orderCode;
    private String customerName;
    private String phone;
    private String flatNo;
    private String addressLine;
    private String landmark;
    private String notes;
    private String status;
    private BigDecimal totalAmount;
    private Instant createdAt;
    private Instant deliveredAt;
    // When this order last changed status — see Order.statusUpdatedAt's
    // own comment. Used by the admin Orders tab to build the next page's
    // keyset cursor and to decide whether "Reopen as New" is still within
    // its 24-hour window, both client-side (see admin-orders.js) mirroring
    // the same rule OrderService.reopen enforces server-side.
    private Instant statusUpdatedAt;
    private List<OrderItemResponse> items;

    public static OrderResponse from(Order order) {
        return OrderResponse.builder()
                .id(order.getId())
                .orderCode(order.getOrderCode())
                .customerName(order.getCustomerName())
                .phone(order.getPhone())
                .flatNo(order.getFlatNo())
                .addressLine(order.getAddressLine())
                .landmark(order.getLandmark())
                .notes(order.getNotes())
                .status(order.getStatus().name())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .deliveredAt(order.getDeliveredAt())
                .statusUpdatedAt(order.getStatusUpdatedAt())
                .items(order.getItems().stream()
                        .map(OrderItemResponse::from)
                        .collect(Collectors.toList()))
                .build();
    }
}
