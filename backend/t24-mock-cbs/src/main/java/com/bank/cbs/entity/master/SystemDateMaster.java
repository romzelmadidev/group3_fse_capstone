package com.bank.cbs.entity.master;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.Instant;

@Entity
@Table(name = "system_dates")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemDateMaster {

    @Id
    @Column(name = "system_date_id", length = 64)
    private String systemDateId;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private String status = "ONLINE"; // ONLINE, EOD_CUTOFF, COB_PROCESSING, ROLLOVER, ERROR_HALTED

    @Column(name = "posting_window_open", nullable = false)
    @Builder.Default
    private Boolean postingWindowOpen = true;

    @Column(name = "last_cob_completed_at")
    private Instant lastCobCompletedAt;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();
}
