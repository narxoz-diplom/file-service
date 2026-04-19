-- Старая таблица files без is_public (FileEntity.isPublic -> колонка is_public)
DO
$$
    BEGIN
        IF EXISTS (SELECT 1
                   FROM information_schema.tables
                   WHERE table_schema = current_schema()
                     AND table_name = 'files')
            AND NOT EXISTS (SELECT 1
                            FROM information_schema.columns
                            WHERE table_schema = current_schema()
                              AND table_name = 'files'
                              AND column_name = 'is_public') THEN
            ALTER TABLE files
                ADD COLUMN is_public boolean NOT NULL DEFAULT false;
        END IF;
    END
$$;
