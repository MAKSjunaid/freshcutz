package com.freshcutz.dto;

import com.freshcutz.entity.Order;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/** Same shape as OrderResponse, but deliberately leaves out customerName
 * and phone — this is what the customer-facing "My Orders" screen gets
 * back (see CustomerLookupController.orders()). A customer looking at
 * their own order history doesn't need their own name/phone echoed back
 * on every single row. */
@Getter
@Builder
@AllArgsConstructor
public class CustomerOrderResponse {

    private String orderCode;
    private String flatNo;
    private String addressLine;
    private String landmark;
    private String notes;
    private String status;
    private BigDecimal totalAmount;
    private Instant createdAt;
    private Instant deliveredAt;
    private List<OrderItemResponse> items;

    public static CustomerOrderResponse from(Order order) {
        return CustomerOrderResponse.builder()
                .orderCode(order.getOrderCode())
                .flatNo(order.getFlatNo())
                .addressLine(order.getAddressLine())
                .landmark(order.getLandmark())
                .notes(order.getNotes())
                .status(order.getStatus().name())
                .totalAmount(order.getTotalAmount())
                .createdAt(order.getCreatedAt())
                .deliveredAt(order.getDeliveredAt())
                .items(order.getItems().stream()
                        .map(OrderItemResponse::from)
                        .collect(Collectors.toList()))
                .build();
    }
}
