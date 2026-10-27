package com.prizm.campaign;

import com.prizm.campaign.dto.RedeemResponse;
import com.prizm.campaign.repository.CampaignRepository;
import com.prizm.campaign.repository.RedemptionRepository;
import com.prizm.campaign.service.AuditClient;
import com.prizm.campaign.service.VoucherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Not @Transactional: each redeem must really commit so concurrent requests can see each other.
// DirtiesContext throws away the in-memory DB afterwards so other test classes start from seed data.
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VoucherConcurrencyTest {

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private RedemptionRepository redemptionRepository;

    @Autowired
    private CampaignRepository campaignRepository;

    @MockBean
    private AuditClient auditClient;

    @Test
    void parallelRequestsFromOneUserCannotExceedLimit() throws Exception {
        // campaign 1 has four ACTIVE vouchers; one user fires all four at once with a limit of 2
        String[] codes = {"RAYA-0001", "RAYA-0002", "RAYA-0003", "RAYA-0006"};
        int stockBefore = campaignRepository.findById(1L).get().getRemainingStock();

        ExecutorService pool = Executors.newFixedThreadPool(codes.length);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<RedeemResponse>> results = new ArrayList<>();
        for (String code : codes) {
            results.add(pool.submit(() -> {
                start.await();
                return voucherService.redeem(code, "user-race");
            }));
        }
        start.countDown();

        int ok = 0;
        for (Future<RedeemResponse> f : results) {
            if ("OK".equals(f.get().getResult())) {
                ok++;
            }
        }
        pool.shutdown();

        assertEquals(2, ok, "successful redemptions");
        assertEquals(2, redemptionRepository.countByCampaignIdAndUserId(1L, "user-race"));
        assertEquals(stockBefore - 2, campaignRepository.findById(1L).get().getRemainingStock());
    }
}
