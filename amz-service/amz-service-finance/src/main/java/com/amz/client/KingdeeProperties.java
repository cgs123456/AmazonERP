package com.amz.client;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 金蝶云星空连接配置。
 * <p>
 * 私钥只允许通过环境变量 / Secret 注入；未配置时连接器必须显式失败，不允许降级为 Mock。
 */
@Data
@Component
@ConfigurationProperties(prefix = "kingdee")
public class KingdeeProperties {

    /** K3Cloud 站点根地址，例如 https://erp.example.com/K3Cloud/。 */
    private String apiGateway;

    /** 数据中心 ID（dbId）。 */
    private String dbId;

    /** 第三方登录用户。 */
    private String userName;

    /** 第三方系统登录授权 AppId。 */
    private String appId;

    /** 第三方系统登录授权 AppSecret。 */
    private String appSecret;

    /** 语言 ID：中文 2052、英文 1033、繁体 3076。 */
    private int lcid = 2052;

    /** 账簿编码，映射 GL_VOUCHER.FAccountBookID.FNumber。 */
    private String accountBookNumber;

    /** 凭证字编码，映射 GL_VOUCHER.FVOUCHERGROUPID.FNumber。 */
    private String voucherGroupNumber;

    /** 本位币编码，映射 GL_VOUCHER.FEntity.FCurrencyID.FNumber。 */
    private String currencyNumber;

    /** 汇率类型编码；配置后写入 FEXCHANGERATETYPE。 */
    private String exchangeRateTypeNumber;

    /**
     * 返回缺失的必填配置键，不包含任何密钥值。
     */
    public List<String> missingRequiredKeys() {
        List<String> missing = new ArrayList<>();
        require(missing, "kingdee.api-gateway", apiGateway);
        require(missing, "kingdee.db-id", dbId);
        require(missing, "kingdee.user-name", userName);
        require(missing, "kingdee.app-id", appId);
        require(missing, "kingdee.app-secret", appSecret);
        require(missing, "kingdee.account-book-number", accountBookNumber);
        require(missing, "kingdee.voucher-group-number", voucherGroupNumber);
        require(missing, "kingdee.currency-number", currencyNumber);
        return missing;
    }

    private static void require(List<String> missing, String key, String value) {
        if (value == null || value.isBlank()) {
            missing.add(key);
        }
    }
}
