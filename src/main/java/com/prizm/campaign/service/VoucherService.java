package com.prizm.campaign.service;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.model.Redemption;
import com.prizm.campaign.model.Voucher;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.repository.VoucherRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;

@Service
public class VoucherService {

    private static final Logger log = LoggerFactory.getLogger(VoucherService.class);

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @Autowired
    private RedemptionRepository redemptionRepository;

    @Autowired
    private AuditClient auditClient;

    @Transactional
    public RedeemResponse redeem(String code, String userId) {

        Long campaignId = voucherRepository.findCampaignIdByCode(code);
        if (campaignId == null) {
            return RedeemResponse.fail("Voucher not found");
        }

        // Lock first, then read: the status, stock and per-user count below are only
        // trustworthy while we hold the campaign row lock (released on commit).
        Campaign campaign = campaignRepository.findByIdForUpdate(campaignId).get();
        Voucher voucher = voucherRepository.findByCode(code);

        if ("REDEEMED".equals(voucher.getStatus())) {
            return RedeemResponse.fail("Voucher already redeemed");
        }

        if ("VOID".equals(voucher.getStatus())) {
            return RedeemResponse.fail("Voucher is void");
        }

        if (!campaign.isActive()) {
            return RedeemResponse.fail("Campaign is not active");
        }

        if (campaign.getRemainingStock() <= 0) {
            return RedeemResponse.fail("Campaign out of stock");
        }

        // Every redemption row counts, including vouchers voided afterwards: void does not
        // restore stock or delete the redemption either, so a void does not give the slot back.
        long userRedemptions = redemptionRepository.countByCampaignIdAndUserId(campaign.getId(), userId);
        if (userRedemptions >= campaign.getMaxRedemptionsPerUser()) {
            return RedeemResponse.fail("Per-user redemption limit reached: max "
                    + campaign.getMaxRedemptionsPerUser() + " voucher(s) per user for this campaign");
        }

        voucher.setStatus("REDEEMED");
        voucher.setRedeemedBy(userId);
        voucher.setRedeemedAt(new Date());
        voucherRepository.save(voucher);

        campaign.setRemainingStock(campaign.getRemainingStock() - 1);
        campaignRepository.save(campaign);

        Redemption redemption = new Redemption(
                voucher.getId(), campaign.getId(), userId, new Date());
        redemptionRepository.save(redemption);

        // Audit only after commit: never hold the campaign lock across a remote call,
        // and never audit a redemption that ends up rolled back.
        String clientCode = campaign.getClientCode();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    auditClient.recordRedemption(clientCode, code, userId);
                } catch (Exception e) {
                    log.error("audit call failed");
                }
            }
        });

        return RedeemResponse.ok(voucher.getCode(), campaign.getRemainingStock());
    }

    public RedeemResponse voidVoucher(String code) {
        Voucher voucher = voucherRepository.findByCode(code);
        if (voucher == null) {
            return RedeemResponse.fail("Voucher not found");
        }
        voucher.setStatus("VOID");
        voucherRepository.save(voucher);
        return RedeemResponse.ok(voucher.getCode(), null);
    }
}
