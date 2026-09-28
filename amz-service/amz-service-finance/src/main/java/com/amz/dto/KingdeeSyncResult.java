package com.amz.dto;

/**
 * 金蝶同步的机器可读结果。
 * <p>
 * 该对象刻意不把「请求处理完成」等同于「真实入账成功」：
 * {@link Status#MOCK} 表示模拟调用，{@link Status#SKIPPED} 表示并发认领失败或幂等跳过，
 * 二者都不允许调用方按真实 {@link Status#SYNCED} 处理。
 *
 * @param status    同步结果状态
 * @param voucherId 会计凭证 ID；凭证不存在时为请求传入的 ID
 * @param kingdeeNo 金蝶返回或回读的凭证号；模拟、跳过、失败时可能为空
 * @param message   面向用户/日志的说明，禁止把模拟成功描述为真实入账
 */
public record KingdeeSyncResult(Status status, Long voucherId, String kingdeeNo, String message) {

    public enum Status {
        /** 真实客户端已确认金蝶入账。 */
        SYNCED,
        /** 模拟客户端完成，仅用于开发/演示，未真实入账。 */
        MOCK,
        /** 已有同步在进行或幂等状态已变化，本次未重复调用金蝶。 */
        SKIPPED,
        /** 已发起调用或尝试处理，但失败。 */
        FAILED,
        /** 凭证不存在。 */
        NOT_FOUND,
        /** 当前用户无权操作该凭证所属店铺。 */
        FORBIDDEN,
        /** 真实连接器缺少必要配置，尚未发起调用。 */
        NOT_CONFIGURED
    }

    public boolean isRealSuccess() {
        return status == Status.SYNCED;
    }

    public boolean isSimulation() {
        return status == Status.MOCK;
    }

    public boolean isFailure() {
        return status == Status.FAILED
                || status == Status.NOT_FOUND
                || status == Status.FORBIDDEN
                || status == Status.NOT_CONFIGURED;
    }

    public static KingdeeSyncResult synced(Long voucherId, String kingdeeNo) {
        return new KingdeeSyncResult(Status.SYNCED, voucherId, kingdeeNo, "金蝶已确认凭证入账");
    }

    public static KingdeeSyncResult mock(Long voucherId, String mockNo) {
        return new KingdeeSyncResult(Status.MOCK, voucherId, mockNo, "模拟同步完成，未真实入账");
    }

    public static KingdeeSyncResult skipped(Long voucherId, String message) {
        return new KingdeeSyncResult(Status.SKIPPED, voucherId, null, message);
    }

    public static KingdeeSyncResult failed(Long voucherId, String message) {
        return new KingdeeSyncResult(Status.FAILED, voucherId, null, message);
    }

    public static KingdeeSyncResult notFound(Long voucherId) {
        return new KingdeeSyncResult(Status.NOT_FOUND, voucherId, null, "凭证不存在");
    }

    public static KingdeeSyncResult forbidden(Long voucherId) {
        return new KingdeeSyncResult(Status.FORBIDDEN, voucherId, null, "无权操作该凭证所属店铺");
    }

    public static KingdeeSyncResult notConfigured(Long voucherId, String message) {
        return new KingdeeSyncResult(Status.NOT_CONFIGURED, voucherId, null,
                "金蝶连接器未配置完整：" + message);
    }
}
