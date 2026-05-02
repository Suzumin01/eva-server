ALTER TABLE ai_responses
    ALTER COLUMN raw_response TYPE text USING raw_response::text;
