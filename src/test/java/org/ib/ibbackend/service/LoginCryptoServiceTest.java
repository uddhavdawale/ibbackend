package org.ib.ibbackend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ib.ibbackend.dto.LoginRequest;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginCryptoServiceTest {

    @Test
    void defaultConstructorShouldAllowSpringStyleInstantiation() {
        LoginCryptoService service = new LoginCryptoService();

        LoginRequest request = new LoginRequest();
        request.setUsername("owner@example.com");
        request.setPassword("secret");

        LoginRequest resolved = service.resolveLoginRequest(request);

        assertThat(resolved).isSameAs(request);
        assertThat(resolved.getUsername()).isEqualTo("owner@example.com");
    }

    @Test
    void resolveLoginRequestShouldKeepPlaintextPayloadWhenCryptoFieldsAreMissing() {
        LoginCryptoService service = new LoginCryptoService(new ObjectMapper(), "");

        LoginRequest request = new LoginRequest();
        request.setUsername("owner@example.com");
        request.setPassword("secret");

        LoginRequest resolved = service.resolveLoginRequest(request);

        assertThat(resolved).isSameAs(request);
        assertThat(resolved.getUsername()).isEqualTo("owner@example.com");
    }

    @Test
    void resolveLoginRequestShouldDecryptEncryptedPayload() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        String privateKeyBase64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        LoginCryptoService service = new LoginCryptoService(new ObjectMapper(), privateKeyBase64);

        byte[] aesKey = new byte[32];
        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(aesKey);
        random.nextBytes(iv);

        String payloadJson = "{\"username\":\"owner@example.com\",\"password\":\"secret\"}";

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] encryptedPayload = aesCipher.doFinal(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, pair.getPublic(), new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey);

        LoginRequest encryptedRequest = new LoginRequest();
        encryptedRequest.setEncryptedPayload(Base64.getEncoder().encodeToString(encryptedPayload));
        encryptedRequest.setEncryptedKey(Base64.getEncoder().encodeToString(encryptedAesKey));
        encryptedRequest.setIv(Base64.getEncoder().encodeToString(iv));

        LoginRequest resolved = service.resolveLoginRequest(encryptedRequest);

        assertThat(resolved.getUsername()).isEqualTo("owner@example.com");
        assertThat(resolved.getPassword()).isEqualTo("secret");
    }

    @Test
    void resolveLoginRequestShouldDecryptEncryptedPayloadUsingClasspathPemFallback() throws Exception {
        LoginCryptoService service = new LoginCryptoService(new ObjectMapper(), "");

        PublicKey publicKey = loadClasspathPublicKey();
        byte[] aesKey = new byte[32];
        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(aesKey);
        random.nextBytes(iv);

        String payloadJson = "{\"username\":\"owner@example.com\",\"password\":\"secret\"}";

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] encryptedPayload = aesCipher.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));

        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, publicKey, new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey);

        LoginRequest encryptedRequest = new LoginRequest();
        encryptedRequest.setEncryptedPayload(Base64.getEncoder().encodeToString(encryptedPayload));
        encryptedRequest.setEncryptedKey(Base64.getEncoder().encodeToString(encryptedAesKey));
        encryptedRequest.setIv(Base64.getEncoder().encodeToString(iv));

        LoginRequest resolved = service.resolveLoginRequest(encryptedRequest);

        assertThat(resolved.getUsername()).isEqualTo("owner@example.com");
        assertThat(resolved.getPassword()).isEqualTo("secret");
    }

    @Test
    void resolveLoginRequestShouldRejectMalformedEncryptedPayload() {
        LoginCryptoService service = new LoginCryptoService(new ObjectMapper(), "");

        LoginRequest request = new LoginRequest();
        request.setEncryptedPayload("!!!");
        request.setEncryptedKey(Base64.getEncoder().encodeToString(new byte[]{4, 5, 6}));
        request.setIv(Base64.getEncoder().encodeToString(new byte[12]));

        assertThatThrownBy(() -> service.resolveLoginRequest(request))
                .isInstanceOf(LoginCryptoService.InvalidLoginCryptoPayloadException.class)
                .hasMessageContaining("Invalid base64 content for encrypted payload");
    }

    @Test
    void resolveLoginRequestShouldSupportLegacyOaepSha1EncryptedKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        String privateKeyBase64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        LoginCryptoService service = new LoginCryptoService(new ObjectMapper(), privateKeyBase64);

        byte[] aesKey = new byte[32];
        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(aesKey);
        random.nextBytes(iv);

        String payloadJson = "{\"username\":\"owner@example.com\",\"password\":\"secret\"}";

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] encryptedPayload = aesCipher.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));

        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, pair.getPublic());
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey);

        LoginRequest encryptedRequest = new LoginRequest();
        encryptedRequest.setEncryptedPayload(Base64.getEncoder().encodeToString(encryptedPayload));
        encryptedRequest.setEncryptedKey(Base64.getEncoder().encodeToString(encryptedAesKey));
        encryptedRequest.setIv(Base64.getEncoder().encodeToString(iv));

        LoginRequest resolved = service.resolveLoginRequest(encryptedRequest);

        assertThat(resolved.getUsername()).isEqualTo("owner@example.com");
        assertThat(resolved.getPassword()).isEqualTo("secret");
    }

    @Test
    void resolveLoginRequestShouldSupportLegacyPkcs1EncryptedKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();

        String privateKeyBase64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        LoginCryptoService service = new LoginCryptoService(new ObjectMapper(), privateKeyBase64);

        byte[] aesKey = new byte[32];
        byte[] iv = new byte[12];
        SecureRandom random = new SecureRandom();
        random.nextBytes(aesKey);
        random.nextBytes(iv);

        String payloadJson = "{\"username\":\"owner@example.com\",\"password\":\"secret\"}";

        Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
        aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
        byte[] encryptedPayload = aesCipher.doFinal(payloadJson.getBytes(StandardCharsets.UTF_8));

        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, pair.getPublic());
        byte[] encryptedAesKey = rsaCipher.doFinal(aesKey);

        LoginRequest encryptedRequest = new LoginRequest();
        encryptedRequest.setEncryptedPayload(Base64.getEncoder().encodeToString(encryptedPayload));
        encryptedRequest.setEncryptedKey(Base64.getEncoder().encodeToString(encryptedAesKey));
        encryptedRequest.setIv(Base64.getEncoder().encodeToString(iv));

        LoginRequest resolved = service.resolveLoginRequest(encryptedRequest);

        assertThat(resolved.getUsername()).isEqualTo("owner@example.com");
        assertThat(resolved.getPassword()).isEqualTo("secret");
    }

    private PublicKey loadClasspathPublicKey() throws Exception {
        try (java.io.InputStream inputStream = getClass().getClassLoader().getResourceAsStream("public_key.pem")) {
            assertThat(inputStream).isNotNull();
            String pem = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            String normalized = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("\n", "")
                    .replace("\r", "")
                    .trim();

            byte[] bytes = Base64.getDecoder().decode(normalized);
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(bytes));
        }
    }
}
