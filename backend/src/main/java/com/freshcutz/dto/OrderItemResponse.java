package com.freshcutz.dto;

import com.freshcutz.entity.OrderItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
@AllArgsConstructor
public class OrderItemResponse {

    private Long productId;
    private String productName;
    private String productImageUrl;
    private String variantLabel;
    private String variant;
    private BigDecimal unitPrice;
    private int quantity;
    private BigDecimal lineTotal;

    public static OrderItemResponse from(OrderItem item) {
        return OrderItemResponse.builder()
                .productId(item.getProductId())
                .productName(item.getProductName())
                .productImageUrl(item.getProductImageUrl())
                .variantLabel(item.getVariantLabel())
                .variant(item.getVariant())
                .unitPrice(item.getUnitPrice())
                .quantity(item.getQuantity())
                .lineTotal(item.getLineTotal())
                .build();
    }
}
