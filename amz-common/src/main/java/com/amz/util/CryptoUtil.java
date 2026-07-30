package com.amz.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM 加密工具类（Spring Bean）。
 * <p>
 * 密钥通过配置项 {@code crypto.key}（来源：环境变量 {@code AMZ_CRYPTO_KEY}）注入，
 * 要求为 32 字节 base64 编码。未配置或格式不合法时拒绝启动。
 * <p>
 * 加密结果格式：base64(12 字节随机 IV + 密文 + 16 字节 GCM 认证标签)。
 * 每次加密均使用随机 IV，保证相同明文产出不同密文。
 * <p>
 * 同时提供静态实例 {@link #getInstance()}，供非 Spring 管理的组件
 * （如 MyBatis {@link org.apache.ibatis.type.TypeHandler}）调用。
 */
@Slf4j
@Component
public class CryptoUtil {

    /** GCM 推荐 IV 长度：12 字节 */
    private static final int GCM_IV_LENGTH = 12;

    /** GCM 认证标签长度：128 bit（16 字节） */
    private static final int GCM_TAG_LENGTH_BITS = 128;

    @Value("${crypto.key:}")
    private String cryptoKey;

    private SecretKey secretKey;

    private SecureRandom secureRandom;

    /** 供非 Spring 管理的组件（如 MyBatis TypeHandler）使用的静态实例 */
    private static volatile CryptoUtil INSTANCE;

    @PostConstruct
    public void init() {
        if (cryptoKey == null || cryptoKey.isBlank()) {
            throw new IllegalStateException(
                    "crypto.key 未配置，拒绝启动。请通过环境变量 AMZ_CRYPTO_KEY 提供 32 字节 base64 编码的密钥。");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(cryptoKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("crypto.key 不是合法的 base64 编码", e);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "crypto.key 解码后必须为 32 字节（AES-256），实际为 " + keyBytes.length + " 字节。");
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
        this.secureRandom = new SecureRandom();
        INSTANCE = this;
        log.info("CryptoUtil 初始化完成（AES-256-GCM）");
    }

    /** 供非 Spring 管理的组件调用 */
    public static CryptoUtil getInstance() {
        if (INSTANCE == null) {
            throw new IllegalStateException("CryptoUtil 尚未初始化，请确认 Spring 容器已启动且配置了 crypto.key。");
        }
        return INSTANCE;
    }

    /**
     * AES-256-GCM 加密。
     *
     * @param plaintext 明文，为 null 时返回 null
     * @return base64(IV + 密文 + GCM tag)
     */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // 拼接 IV + (密文 + GCM tag)，GCM 模式下 doFinal 输出已包含认证标签
            byte[] combined = new byte[GCM_IV_LENGTH + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LENGTH);
            System.arraycopy(cipherText, 0, combined, GCM_IV_LENGTH, cipherText.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("AES-256-GCM 加密失败", e);
        }
    }

    /**
     * AES-256-GCM 解密。
     *
     * @param ciphertext base64(IV + 密文 + GCM tag)，为 null 时返回 null
     * @return 明文
     */
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertext);
            if (combined.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException(
                        "密文长度不足，至少需要 " + GCM_IV_LENGTH + " 字节 IV，实际为 " + combined.length + " 字节");
            }
            byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_LENGTH);
            byte[] cipherText = Arrays.copyOfRange(combined, GCM_IV_LENGTH, combined.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("AES-256-GCM 解密失败", e);
        }
    }
}
