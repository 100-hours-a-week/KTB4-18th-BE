-- Keep existing rooms and future inserts on the same V2 capacity policy.
-- Existing room identity, status and participation/history records remain unchanged.
ALTER TABLE chat_rooms ALTER COLUMN capacity SET DEFAULT 1000;

UPDATE chat_rooms SET capacity = 1000 WHERE capacity <> 1000;
