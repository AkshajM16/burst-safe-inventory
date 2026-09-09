CREATE TABLE items (
    id              BIGSERIAL PRIMARY KEY,
    sku             TEXT NOT NULL UNIQUE,
    initial_stock   INTEGER NOT NULL CHECK (initial_stock > 0),
    stock           INTEGER NOT NULL CHECK (stock >= 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK (stock <= initial_stock)
);

CREATE TYPE reservation_status AS ENUM ('SUCCEEDED', 'SOLD_OUT');

CREATE TABLE reservations (
    reservation_id UUID PRIMARY KEY,
    item_id         BIGINT NOT NULL REFERENCES items(id) ON DELETE CASCADE,
    requested_qty   INTEGER NOT NULL CHECK (requested_qty > 0),
    reserved_qty    INTEGER NOT NULL CHECK (reserved_qty >= 0),
    status          reservation_status NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK ((status = 'SUCCEEDED' AND reserved_qty = requested_qty)
        OR (status = 'SOLD_OUT' AND reserved_qty = 0))
);

CREATE INDEX reservations_item_id_idx ON reservations(item_id);
