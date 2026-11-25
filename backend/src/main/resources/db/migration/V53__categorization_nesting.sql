-- REQ 7.1 / 7.4: categories can be nested; moving a category under another is part of the same draft/approval batch.
ALTER TABLE categorization_line ALTER COLUMN designation_id DROP NOT NULL;
ALTER TABLE categorization_line ADD COLUMN line_type VARCHAR(20) NOT NULL DEFAULT 'DESIGNATION'
    CHECK (line_type IN ('DESIGNATION', 'CATEGORY'));
ALTER TABLE categorization_line ADD COLUMN category_id BIGINT REFERENCES position_category (id);
ALTER TABLE categorization_line ADD COLUMN from_parent_id BIGINT REFERENCES position_category (id);
ALTER TABLE categorization_line ADD COLUMN to_parent_id BIGINT REFERENCES position_category (id);
ALTER TABLE categorization_line ADD CONSTRAINT ck_categorization_line_target
    CHECK ((line_type = 'DESIGNATION' AND designation_id IS NOT NULL) OR (line_type = 'CATEGORY' AND category_id IS NOT NULL));
