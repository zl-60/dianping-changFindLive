-- Additive schema only. No DROP/TRUNCATE, and no writes to original tb_* tables.
CREATE TABLE IF NOT EXISTS pet_store (
 id INT PRIMARY KEY, name VARCHAR(80) NOT NULL, address VARCHAR(200) NOT NULL,
 phone VARCHAR(30) NOT NULL, description VARCHAR(500) NOT NULL,
 open_hour INT NOT NULL, close_hour INT NOT NULL
);
CREATE TABLE IF NOT EXISTS pet_lock (id INT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS pet_spec (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, title VARCHAR(80) NOT NULL,
 category VARCHAR(30) NOT NULL, species VARCHAR(10) NOT NULL,
 size_label VARCHAR(30) NOT NULL, min_weight DECIMAL(6,2) NOT NULL,
 max_weight DECIMAL(6,2) NOT NULL, price_cents INT NOT NULL,
 duration_hours INT NOT NULL, description VARCHAR(500) NOT NULL, active INT NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS pet_staff (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(40) NOT NULL,
 skills VARCHAR(200) NOT NULL, active INT NOT NULL DEFAULT 1
);
CREATE TABLE IF NOT EXISTS pet_shift (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, staff_id BIGINT NOT NULL,
 work_date DATE NOT NULL, start_hour INT NOT NULL, end_hour INT NOT NULL,
 UNIQUE (staff_id, work_date), FOREIGN KEY (staff_id) REFERENCES pet_staff(id)
);
CREATE TABLE IF NOT EXISTS pet_order (
 id VARCHAR(32) PRIMARY KEY, user_phone VARCHAR(11) NOT NULL,
 request_key VARCHAR(64) NOT NULL, spec_id BIGINT NOT NULL,
 service_title VARCHAR(80) NOT NULL, size_label VARCHAR(30) NOT NULL,
 species VARCHAR(10) NOT NULL, price_cents INT NOT NULL, duration_hours INT NOT NULL,
 pet_name VARCHAR(40) NOT NULL, breed VARCHAR(60) NOT NULL,
 weight DECIMAL(6,2) NOT NULL, notes VARCHAR(500) NOT NULL,
 contact_name VARCHAR(40) NOT NULL, contact_phone VARCHAR(11) NOT NULL,
 staff_id BIGINT NOT NULL, staff_name VARCHAR(40) NOT NULL,
 starts_at TIMESTAMP NOT NULL, ends_at TIMESTAMP NOT NULL,
 status VARCHAR(30) NOT NULL, created_at TIMESTAMP NOT NULL,
 deadline TIMESTAMP, paid_at TIMESTAMP, confirmed_at TIMESTAMP,
 arrived_at TIMESTAMP, started_at TIMESTAMP, completed_at TIMESTAMP,
 cancelled_at TIMESTAMP, cancel_reason VARCHAR(200),
 paid INT NOT NULL DEFAULT 0, refunded INT NOT NULL DEFAULT 0,
 booking_code VARCHAR(12) NOT NULL UNIQUE,
 UNIQUE (user_phone, request_key),
 FOREIGN KEY (spec_id) REFERENCES pet_spec(id), FOREIGN KEY (staff_id) REFERENCES pet_staff(id)
);
CREATE TABLE IF NOT EXISTS pet_slot (
 staff_id BIGINT NOT NULL, slot_start TIMESTAMP NOT NULL, order_id VARCHAR(32) NOT NULL,
 PRIMARY KEY (staff_id, slot_start),
 FOREIGN KEY (staff_id) REFERENCES pet_staff(id), FOREIGN KEY (order_id) REFERENCES pet_order(id)
);
CREATE TABLE IF NOT EXISTS pet_review (
 order_id VARCHAR(32) PRIMARY KEY, stars INT NOT NULL,
 content VARCHAR(500) NOT NULL, created_at TIMESTAMP NOT NULL,
 FOREIGN KEY (order_id) REFERENCES pet_order(id)
);
CREATE TABLE IF NOT EXISTS pet_session (
 token VARCHAR(64) PRIMARY KEY, subject VARCHAR(40) NOT NULL,
 role VARCHAR(12) NOT NULL, expires_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS pet_login_code (
 phone VARCHAR(11) PRIMARY KEY, code_hash VARCHAR(64) NOT NULL,
 expires_at TIMESTAMP NOT NULL, issued_at TIMESTAMP NOT NULL, attempts INT NOT NULL DEFAULT 0
);
