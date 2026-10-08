package com.bank.cbs.entity.master;

import lombok.*;
import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class GlBalanceId implements Serializable {
    private String glCode;
    private String fiscalPeriod;
}
