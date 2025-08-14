package com.copydrop.android.security

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 기본적인 보안 기능을 제공하는 클래스
 */
class SecurityManager {
    
    companion object {
        private const val ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
        private const val KEY_LENGTH = 256
        private const val IV_LENGTH = 16
    }
    
    /**
     * 비밀번호로부터 암호화 키 생성
     */
    fun generateKeyFromPassword(password: String): SecretKey {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(password.toByteArray())
        return SecretKeySpec(keyBytes, ALGORITHM)
    }
    
    /**
     * 랜덤 암호화 키 생성
     */
    fun generateRandomKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(ALGORITHM)
        keyGenerator.init(KEY_LENGTH)
        return keyGenerator.generateKey()
    }
    
    /**
     * 데이터 암호화
     */
    fun encrypt(data: String, key: SecretKey): EncryptedData? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            
            // 랜덤 IV 생성
            val iv = ByteArray(IV_LENGTH)
            SecureRandom().nextBytes(iv)
            val ivSpec = IvParameterSpec(iv)
            
            cipher.init(Cipher.ENCRYPT_MODE, key, ivSpec)
            val encryptedBytes = cipher.doFinal(data.toByteArray())
            
            EncryptedData(
                data = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP),
                iv = Base64.encodeToString(iv, Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 데이터 복호화
     */
    fun decrypt(encryptedData: EncryptedData, key: SecretKey): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val iv = Base64.decode(encryptedData.iv, Base64.NO_WRAP)
            val ivSpec = IvParameterSpec(iv)
            
            cipher.init(Cipher.DECRYPT_MODE, key, ivSpec)
            val encryptedBytes = Base64.decode(encryptedData.data, Base64.NO_WRAP)
            val decryptedBytes = cipher.doFinal(encryptedBytes)
            
            String(decryptedBytes)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * 해시 생성 (무결성 검증용)
     */
    fun generateHash(data: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(data.toByteArray())
            Base64.encodeToString(hashBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            data.hashCode().toString()
        }
    }
    
    /**
     * 간단한 토큰 생성 (인증용)
     */
    fun generateToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    
    /**
     * 토큰 검증
     */
    fun validateToken(token: String): Boolean {
        return try {
            val decoded = Base64.decode(token, Base64.NO_WRAP)
            decoded.size == 32
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * 암호화된 데이터를 담는 데이터 클래스
 */
data class EncryptedData(
    val data: String,
    val iv: String
)
