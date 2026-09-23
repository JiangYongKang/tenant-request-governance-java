package com.github.highcumontoa.tenantrequestgovernancejava.idempotency;

/**
 * 幂等执行结果包装。
 *
 * @param replayed true 表示本次为重复提交，返回的是首次结果，业务副作用未再次执行
 */
public record IdempotentResult<T>(T value, boolean replayed) {
}
