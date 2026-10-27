package com.prizm.campaign.repository;

import com.prizm.campaign.model.Voucher;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface VoucherRepository extends JpaRepository<Voucher, Long> {

    Voucher findByCode(String code);

    // scalar lookup so the Voucher entity is only loaded after the campaign lock is held
    @Query("select v.campaignId from Voucher v where v.code = :code")
    Long findCampaignIdByCode(String code);

    List<Voucher> findByCampaignId(Long campaignId);
}
