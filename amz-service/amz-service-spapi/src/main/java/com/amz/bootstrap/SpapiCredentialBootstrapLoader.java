package com.amz.bootstrap;

import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.amz.credential.ShopCredentialValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * 从显式 JSON 文件导入 SP-API 店铺凭证。
 * <p>
 * 该组件只负责解析与校验，不接触 Web 请求；生产首次部署由 bootstrap profile 中的
 * ApplicationRunner 调用。整批凭证先完成结构校验再写库，避免因输入错误产生部分导入。
 * 重复执行是幂等的：相同 shopId 会覆盖更新。
 */
public final class SpapiCredentialBootstrapLoader {

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "shopId", "clientId", "clientSecret", "refreshToken",
            "accessKey", "secretKey", "region", "marketplaceId", "sellerId");

    private final ObjectMapper objectMapper;

    public SpapiCredentialBootstrapLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取并写入整批凭证。
     *
     * @return 成功写入的凭证数量
     */
    public int load(Path credentialFile, ShopCredentialStore store) {
        if (credentialFile == null) {
            throw new IllegalStateException("spapi.bootstrap.credential-file 未配置");
        }
        if (!Files.isRegularFile(credentialFile)) {
            throw new IllegalStateException("凭证导入文件不存在或不是普通文件：" + credentialFile);
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(credentialFile.toFile());
        } catch (IOException e) {
            throw new IllegalArgumentException("凭证导入文件不是合法 JSON", e);
        }
        if (root == null || root.isNull()) {
            throw new IllegalArgumentException("凭证导入文件为空");
        }

        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(nodes::add);
        } else if (root.isObject()) {
            nodes.add(root);
        } else {
            throw new IllegalArgumentException("凭证导入文件顶层必须是 JSON 对象或数组");
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("凭证导入文件不包含任何凭证");
        }

        List<ShopCredential> credentials = new ArrayList<>(nodes.size());
        Set<Long> shopIds = new HashSet<>();
        for (int index = 0; index < nodes.size(); index++) {
            ShopCredential credential = parseCredential(nodes.get(index), index);
            if (!shopIds.add(credential.getShopId())) {
                throw new IllegalArgumentException("凭证导入文件存在重复 shopId=" + credential.getShopId());
            }
            List<String> problems = ShopCredentialValidator.validate(credential);
            if (!problems.isEmpty()) {
                throw new IllegalArgumentException("凭证导入文件第 " + (index + 1)
                        + " 条 shopId=" + credential.getShopId()
                        + " 结构不完整，字段问题=" + String.join(",", problems));
            }
            credentials.add(credential);
        }

        store.putAll(credentials);
        return credentials.size();
    }

    private ShopCredential parseCredential(JsonNode node, int index) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("凭证导入文件第 " + (index + 1) + " 条必须是 JSON 对象");
        }
        Iterator<String> fieldNames = node.fieldNames();
        while (fieldNames.hasNext()) {
            String field = fieldNames.next();
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("凭证导入文件第 " + (index + 1)
                        + " 条包含不支持的字段：" + field);
            }
        }

        JsonNode shopIdNode = node.get("shopId");
        if (shopIdNode == null || shopIdNode.isNull() || !shopIdNode.canConvertToLong()) {
            throw new IllegalArgumentException("凭证导入文件第 " + (index + 1) + " 条缺少合法 shopId");
        }
        long shopId = shopIdNode.asLong();
        if (shopId <= 0) {
            throw new IllegalArgumentException("凭证导入文件第 " + (index + 1) + " 条 shopId 必须大于 0");
        }

        ShopCredential credential = new ShopCredential();
        credential.setShopId(shopId);
        credential.setClientId(textOrNull(node, "clientId", index));
        credential.setClientSecret(textOrNull(node, "clientSecret", index));
        credential.setRefreshToken(textOrNull(node, "refreshToken", index));
        credential.setAccessKey(textOrNull(node, "accessKey", index));
        credential.setSecretKey(textOrNull(node, "secretKey", index));
        credential.setRegion(textOrNull(node, "region", index));
        credential.setMarketplaceId(textOrNull(node, "marketplaceId", index));
        credential.setSellerId(textOrNull(node, "sellerId", index));
        return credential;
    }

    private String textOrNull(JsonNode node, String field, int index) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("凭证导入文件第 " + (index + 1)
                    + " 条字段 " + field + " 必须是字符串");
        }
        return value.textValue();
    }
}
