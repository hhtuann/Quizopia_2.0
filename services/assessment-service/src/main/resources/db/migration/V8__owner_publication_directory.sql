-- Immutable created_at plus UUID provides stable keyset order even when drafts are edited/opened/closed.
CREATE INDEX publications_owner_created_idx ON publications(owner_id,created_at DESC,id DESC);
CREATE INDEX publications_owner_state_created_idx ON publications(owner_id,state,created_at DESC,id DESC);
