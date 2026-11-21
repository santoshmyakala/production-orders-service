-- Production orders for a plant: planned by production planning, executed on a line.
CREATE SEQUENCE production_order_number_seq;

CREATE TABLE production_orders (
    id                 uuid PRIMARY KEY,
    order_number       text        NOT NULL UNIQUE,
    product_code       text        NOT NULL,
    quantity           integer     NOT NULL CHECK (quantity > 0),
    line               text        NOT NULL,
    due_date           date        NOT NULL,
    status             text        NOT NULL CHECK (status IN ('PLANNED', 'RELEASED', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED')),
    reservation_id     text,
    good_quantity      integer CHECK (good_quantity >= 0),
    scrap_quantity     integer CHECK (scrap_quantity >= 0),
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    version            bigint      NOT NULL,
    -- a released order always has its material reserved
    CONSTRAINT released_orders_have_material CHECK (status IN ('PLANNED', 'CANCELLED') OR reservation_id IS NOT NULL)
);
CREATE INDEX production_orders_status_line_idx ON production_orders (status, line, due_date);

-- Idempotency-Key for POST /api/orders: a retried request returns the original order; the same key
-- with a different body is rejected.
CREATE TABLE idempotency_keys (
    idempotency_key text        PRIMARY KEY,
    request_hash    text        NOT NULL,
    order_id        uuid        NOT NULL REFERENCES production_orders (id),
    created_at      timestamptz NOT NULL
);

-- Transactional outbox: events are written in the same transaction as the state change and
-- relayed to the message broker afterwards (at-least-once; consumers dedupe on event id).
CREATE TABLE outbox_events (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     uuid        NOT NULL UNIQUE,
    aggregate_id uuid        NOT NULL,
    event_type   text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL,
    published_at timestamptz
);
CREATE INDEX outbox_unpublished_idx ON outbox_events (id) WHERE published_at IS NULL;
