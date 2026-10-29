-- Preserve replay identity without retaining quiz text after primary erasure.
ALTER TABLE quiz_deletion_operations ADD COLUMN request_digest varchar(64);
UPDATE quiz_deletion_operations
SET request_digest = encode(sha256(convert_to(confirmed_title, 'UTF8')), 'hex')
WHERE confirmed_title IS NOT NULL;
-- Already-scrubbed historical receipts have no reconstructible request identity:
-- their digest stays NULL, so replay fails closed; owner status remains available.
