ALTER TABLE terms_agreements
    ADD COLUMN accepted_version VARCHAR(20) NULL AFTER terms_id;
