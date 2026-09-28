package com.amz.bootstrap;

import com.amz.credential.ShopCredential;
import com.amz.credential.ShopCredentialStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("SP-API 凭证一次性导入器：显式、幂等、写入前全量校验")
class SpapiCredentialBootstrapLoaderTest {

    @TempDir
    Path tempDir;

    private ShopCredentialStore store;
    private SpapiCredentialBootstrapLoader loader;

    @BeforeEach
    void setUp() {
        store = mock(ShopCredentialStore.class);
        loader = new SpapiCredentialBootstrapLoader(new ObjectMapper());
    }

    @Test
    @DisplayName("单个 JSON 对象导入后写入完整凭证")
    void importsSingleCredentialObject() throws Exception {
        Path file = write("credential.json", """
                {
                  "shopId": 1001,
                  "clientId": "amzn1.application-oa2-client.test",
                  "clientSecret": "client-secret",
                  "refreshToken": "refresh-token",
                  "marketplaceId": "ATVPDKIKX0DER",
                  "region": "NA",
                  "sellerId": "A1TESTSHOP"
                }
                """);

        int count = loader.load(file, store);

        assertEquals(1, count);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShopCredential>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).putAll(captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals(1001L, captor.getValue().get(0).getShopId());
        assertEquals("ATVPDKIKX0DER", captor.getValue().get(0).getMarketplaceId());
    }

    @Test
    @DisplayName("JSON 数组可一次导入多个店铺")
    void importsCredentialArray() throws Exception {
        Path file = write("credentials.json", """
                [
                  {
                    "shopId": 1001,
                    "clientId": "client-1",
                    "clientSecret": "secret-1",
                    "refreshToken": "refresh-1",
                    "marketplaceId": "ATVPDKIKX0DER"
                  },
                  {
                    "shopId": 1002,
                    "clientId": "client-2",
                    "clientSecret": "secret-2",
                    "refreshToken": "refresh-2",
                    "region": "EU"
                  }
                ]
                """);

        int count = loader.load(file, store);

        assertEquals(2, count);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShopCredential>> captor = ArgumentCaptor.forClass(List.class);
        verify(store).putAll(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    @DisplayName("未知字段必须拒绝，防止拼写错误被静默忽略")
    void rejectsUnknownFieldBeforeWritingAnything() throws Exception {
        Path file = write("unknown.json", """
                {
                  "shopId": 1001,
                  "clientId": "client",
                  "clientSecret": "secret",
                  "refreshToken": "refresh",
                  "marketplaceId": "ATVPDKIKX0DER",
                  "clientIdd": "typo"
                }
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> loader.load(file, store));

        assertTrue(ex.getMessage().contains("clientIdd"));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("任一凭证字段残缺时整批拒绝，不得部分写入")
    void rejectsIncompleteBatchBeforeWritingAnything() throws Exception {
        Path file = write("incomplete.json", """
                [
                  {
                    "shopId": 1001,
                    "clientId": "client-1",
                    "clientSecret": "secret-1",
                    "refreshToken": "refresh-1",
                    "marketplaceId": "ATVPDKIKX0DER"
                  },
                  {
                    "shopId": 1002,
                    "clientId": "client-2",
                    "clientSecret": "secret-2",
                    "marketplaceId": "ATVPDKIKX0DER"
                  }
                ]
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> loader.load(file, store));

        assertTrue(ex.getMessage().contains("refreshToken"));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("同一批次重复 shopId 必须拒绝，避免后写静默覆盖前写")
    void rejectsDuplicateShopId() throws Exception {
        Path file = write("duplicate.json", """
                [
                  {
                    "shopId": 1001,
                    "clientId": "client-1",
                    "clientSecret": "secret-1",
                    "refreshToken": "refresh-1",
                    "region": "NA"
                  },
                  {
                    "shopId": 1001,
                    "clientId": "client-2",
                    "clientSecret": "secret-2",
                    "refreshToken": "refresh-2",
                    "region": "EU"
                  }
                ]
                """);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> loader.load(file, store));

        assertTrue(ex.getMessage().contains("1001"));
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("凭证文件不存在时显式失败，不回落到环境变量或空导入")
    void rejectsMissingFile() {
        Path file = tempDir.resolve("missing.json");

        assertThrows(IllegalStateException.class, () -> loader.load(file, store));
        verifyNoInteractions(store);
    }

    private Path write(String name, String json) throws Exception {
        Path file = tempDir.resolve(name);
        Files.writeString(file, json);
        return file;
    }
}