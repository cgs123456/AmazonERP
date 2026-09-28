package com.amz.credential;

import com.amz.connector.MarketplaceRegistry;
import com.amz.connector.UnknownMarketplaceException;

import java.util.ArrayList;
import java.util.List;

/**
 * SP-API 店铺凭证的结构完整性校验（fail-closed）。
 * <p>
 * 只返回缺失或冲突的字段名，不返回字段值；调用方可据此拒绝写入并在日志中避免泄露凭据。
 * LWA 三要素为必需项；路由必须显式提供已登记的 marketplaceId 或合法 region；
 * AWS accessKey/secretKey 可省略，但不得只配置其中一个。
 */
public final class ShopCredentialValidator {

    private ShopCredentialValidator() {
    }

    /**
     * 校验凭证结构。
     *
     * @param credential 待校验凭证，可为 null
     * @return 稳定顺序的字段问题列表；空列表表示通过
     */
    public static List<String> validate(ShopCredential credential) {
        if (credential == null) {
            return List.of("credential");
        }

        List<String> problems = new ArrayList<>();
        if (isBlank(credential.getClientId())) {
            problems.add("clientId");
        }
        if (isBlank(credential.getClientSecret())) {
            problems.add("clientSecret");
        }
        if (isBlank(credential.getRefreshToken())) {
            problems.add("refreshToken");
        }

        String marketplaceId = credential.getMarketplaceId();
        String region = credential.getRegion();
        boolean marketplacePresent = !isBlank(marketplaceId);
        boolean regionPresent = !isBlank(region);

        if (!marketplacePresent && !regionPresent) {
            problems.add("marketplaceId/region");
        }

        String marketplaceRegion = null;
        if (marketplacePresent) {
            if (!MarketplaceRegistry.knownMarketplaceIds().contains(marketplaceId)) {
                problems.add("marketplaceId");
            } else {
                try {
                    marketplaceRegion = MarketplaceRegistry.resolveRegion(marketplaceId);
                } catch (UnknownMarketplaceException e) {
                    problems.add("marketplaceId");
                }
            }
        }

        boolean regionValid = false;
        if (regionPresent) {
            try {
                MarketplaceRegistry.resolveHost(region);
                regionValid = true;
            } catch (UnknownMarketplaceException e) {
                problems.add("region");
            }
        }

        if (marketplaceRegion != null && regionValid && !marketplaceRegion.equals(region)) {
            problems.add("region");
        }

        boolean accessKeyPresent = !isBlank(credential.getAccessKey());
        boolean secretKeyPresent = !isBlank(credential.getSecretKey());
        if (accessKeyPresent != secretKeyPresent) {
            problems.add("accessKey/secretKey");
        }

        return List.copyOf(problems);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
