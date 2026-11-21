package io.github.santoshmyakala.orders.domain;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProductionOrderRepository extends JpaRepository<ProductionOrder, UUID> {

    @Query("""
            select o from ProductionOrder o
            where (:status is null or o.status = :status) and (:line is null or o.line = :line)
            order by o.dueDate, o.orderNumber""")
    Page<ProductionOrder> search(OrderStatus status, String line, Pageable pageable);

    @Query(value = "select nextval('production_order_number_seq')", nativeQuery = true)
    long nextOrderNumber();
}
