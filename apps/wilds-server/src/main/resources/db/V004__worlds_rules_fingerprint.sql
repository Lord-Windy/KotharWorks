-- Ticket 11 V004: pin each world to the rules catalog fingerprint of the binary that created it.
-- Nullable: worlds created before this migration keep NULL and match on the version triple alone.
ALTER TABLE wilds_meta.worlds ADD COLUMN rules_fingerprint BIGINT;
