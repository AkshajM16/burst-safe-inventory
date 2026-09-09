package dev.portfolio.inventory;

import dev.portfolio.inventory.ApiModels.ItemView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

@Repository
public class InventoryRepository {
    private final JdbcTemplate jdbc;

    public InventoryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public ItemView create(String sku, int initialStock) {
        return jdbc.queryForObject("""
            INSERT INTO items(sku, initial_stock, stock) VALUES (?, ?, ?)
            RETURNING id, sku, initial_stock, stock, created_at
            """, (rs, row) -> mapItem(rs, 0, 0), sku, initialStock, initialStock);
    }

    public Optional<ItemView> find(long id) {
        return jdbc.query("""
            SELECT i.id, i.sku, i.initial_stock, i.stock, i.created_at,
                   count(r.*) FILTER (WHERE r.status = 'SUCCEEDED') AS success_count,
                   coalesce(sum(r.reserved_qty), 0) AS reserved_qty
              FROM items i LEFT JOIN reservations r ON r.item_id = i.id
             WHERE i.id = ? GROUP BY i.id
            """, (rs, row) -> mapItem(rs, rs.getLong("success_count"), rs.getLong("reserved_qty")), id)
            .stream().findFirst();
    }

    private static ItemView mapItem(ResultSet rs, long successes, long quantity) throws SQLException {
        return new ItemView(rs.getLong("id"), rs.getString("sku"), rs.getInt("initial_stock"),
            rs.getInt("stock"), successes, quantity, rs.getTimestamp("created_at").toInstant());
    }
}
