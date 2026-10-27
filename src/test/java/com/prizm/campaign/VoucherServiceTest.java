package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.model.Campaign;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.service.AuditClient;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Each test rolls back, so tests no longer depend on each other's leftover state.
@SpringBootTest
@Transactional
class VoucherServiceTest {

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private CampaignRepository campaignRepository;

    // the real client calls an internal host that is unreachable from dev/CI
    @MockBean
    private AuditClient auditClient;

    @Test
    void redeemActiveVoucherSucceeds() {
        RedeemResponse res = voucherService.redeem("RAYA-0001", "user-1");
        assertEquals("OK", res.getResult());
    }

    @Test
    void redeemAlreadyRedeemedVoucherFails() {
        RedeemResponse res = voucherService.redeem("RAYA-0004", "user-2");
        assertEquals("FAILED", res.getResult());
    }

    @Test
    void redeemUnknownCodeFails() {
        RedeemResponse res = voucherService.redeem("NOPE-9999", "user-3");
        assertEquals("FAILED", res.getResult());
    }

    @Test
    void existingCampaignsDefaultToLimitOfTwo() {
        for (Campaign c : campaignRepository.findAll()) {
            assertEquals(2, c.getMaxRedemptionsPerUser(), "campaign " + c.getId());
        }
    }

    @Test
    void userCanRedeemUpToLimitThenIsRejected() {
        assertEquals("OK", voucherService.redeem("RAYA-0001", "user-10").getResult());
        assertEquals("OK", voucherService.redeem("RAYA-0002", "user-10").getResult());

        RedeemResponse third = voucherService.redeem("RAYA-0003", "user-10");
        assertEquals("FAILED", third.getResult());
        assertTrue(third.getMessage().contains("limit"), third.getMessage());
    }

    @Test
    void rejectedRedemptionDoesNotConsumeVoucherOrStock() {
        voucherService.redeem("RAYA-0001", "user-11");
        voucherService.redeem("RAYA-0002", "user-11");
        int stockBefore = campaignRepository.findById(1L).get().getRemainingStock();

        assertEquals("FAILED", voucherService.redeem("RAYA-0003", "user-11").getResult());

        assertEquals(stockBefore, campaignRepository.findById(1L).get().getRemainingStock());
        // the voucher is still available to someone else
        assertEquals("OK", voucherService.redeem("RAYA-0003", "user-12").getResult());
    }

    @Test
    void existingHistoryCountsTowardLimitIncludingVoidedVoucher() {
        // seed data: user-99 has two redemptions on campaign 1 (RAYA-0004 redeemed, RAYA-0005 later voided)
        RedeemResponse res = voucherService.redeem("RAYA-0001", "user-99");
        assertEquals("FAILED", res.getResult());
        assertTrue(res.getMessage().contains("limit"), res.getMessage());
    }

    @Test
    void limitIsPerCampaign() {
        // user-99 is at the limit on campaign 1 but has no history on campaign 2
        assertEquals("OK", voucherService.redeem("MRDK-0001", "user-99").getResult());
    }

    @Test
    void limitIsConfigurablePerCampaign() {
        Campaign nova = campaignRepository.findById(2L).get();
        nova.setMaxRedemptionsPerUser(1);
        campaignRepository.saveAndFlush(nova);

        assertEquals("OK", voucherService.redeem("MRDK-0001", "user-20").getResult());
        RedeemResponse second = voucherService.redeem("MRDK-0002", "user-20");
        assertEquals("FAILED", second.getResult());
        assertTrue(second.getMessage().contains("max 1"), second.getMessage());
    }
}
