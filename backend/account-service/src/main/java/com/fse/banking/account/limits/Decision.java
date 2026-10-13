package com.fse.banking.account.limits;

public record Decision(boolean allowed, Breach breach) {
}
