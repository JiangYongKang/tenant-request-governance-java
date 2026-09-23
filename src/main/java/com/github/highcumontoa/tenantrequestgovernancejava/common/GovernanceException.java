package com.github.highcumontoa.tenantrequestgovernancejava.common;

/**
 * 治理链路抛出的异常，携带明确的拒绝原因。
 */
public class GovernanceException extends RuntimeException {

    private final RejectReason reason;

    public GovernanceException(RejectReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RejectReason getReason() {
        return reason;
    }
}
