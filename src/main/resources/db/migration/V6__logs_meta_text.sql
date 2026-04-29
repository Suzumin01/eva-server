-- meta was jsonb with GIN index — drop parent index (cascades to partitions), then alter parent (propagates to partitions)
DROP INDEX IF EXISTS idx_logs_meta_gin;
ALTER TABLE logs ALTER COLUMN meta TYPE text USING meta::text;
