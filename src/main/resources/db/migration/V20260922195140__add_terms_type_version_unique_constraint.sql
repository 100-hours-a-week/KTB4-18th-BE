ALTER TABLE terms
    ADD CONSTRAINT uk_terms_type_version UNIQUE (type, version);
