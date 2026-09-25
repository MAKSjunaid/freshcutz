package com.freshcutz.repository;

import com.freshcutz.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

/** Phone is the id, so findById(phone)/save(...) already give the
 * lookup-by-phone and upsert behavior CustomerService needs — no custom
 * query methods required here. */
public interface CustomerRepository extends JpaRepository<Customer, String> {
}
