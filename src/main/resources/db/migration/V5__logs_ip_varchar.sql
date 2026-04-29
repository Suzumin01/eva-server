-- ip_address was declared as inet but Exposed passes varchar — change to varchar(45)
ALTER TABLE logs ALTER COLUMN ip_address TYPE varchar(45) USING ip_address::text;
