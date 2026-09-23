package com.freshcutz.repository;

import com.freshcutz.entity.Order;
import com.freshcutz.entity.OrderStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /* @EntityGraph loads the lazy `items` collection in the same query
     * instead of leaving it as a lazy proxy. Without it, code that reads
     * an order's items after the fetching transaction has closed (as the
     * xlsx export does) hits "could not initialize proxy - no Session". */
    @EntityGraph(attributePaths = "items")
    List<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status);

    @EntityGraph(attributePaths = "items")
    List<Order> findAllByOrderByCreatedAtDesc();

    Optional<Order> findByOrderCode(String orderCode);

    List<Order> findByCreatedAtGreaterThanEqualAndStatusNot(Instant from, OrderStatus excludedStatus);

    @EntityGraph(attributePaths = "items")
    List<Order> findByCreatedAtBetweenOrderByCreatedAtDesc(Instant from, Instant to);

    long countByOrderCodeStartingWith(String prefix);
}
