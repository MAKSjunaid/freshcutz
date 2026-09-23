package com.freshcutz.repository;

import com.freshcutz.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {

    List<Product> findByEnabledTrueOrderByDisplayOrderAscIdAsc();

    List<Product> findAllByOrderByDisplayOrderAscIdAsc();
}
