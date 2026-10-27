DROP TABLE IF EXISTS redemption;
DROP TABLE IF EXISTS voucher;
DROP TABLE IF EXISTS campaign;

CREATE TABLE campaign (
    id              BIGINT PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    client_code     VARCHAR(50)  NOT NULL,
    total_stock     INT          NOT NULL,
    remaining_stock INT          NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    -- max vouchers one user may redeem in this campaign; existing campaigns get 2
    max_redemptions_per_user INT NOT NULL DEFAULT 2 CHECK (max_redemptions_per_user > 0)
);

CREATE TABLE voucher (
    id          BIGINT PRIMARY KEY,
    campaign_id BIGINT       NOT NULL,
    code        VARCHAR(40)  NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    redeemed_by VARCHAR(80),
    redeemed_at TIMESTAMP
);

CREATE TABLE redemption (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    voucher_id  BIGINT      NOT NULL,
    campaign_id BIGINT      NOT NULL,
    user_id     VARCHAR(80) NOT NULL,
    created_at  TIMESTAMP   NOT NULL
);

-- supports the per-user limit check (count by campaign + user) on every redeem
CREATE INDEX idx_redemption_campaign_user ON redemption (campaign_id, user_id);
