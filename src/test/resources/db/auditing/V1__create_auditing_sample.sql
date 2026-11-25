CREATE TABLE auditing_sample (
    id BIGINT PRIMARY KEY,
    label VARCHAR(255),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
