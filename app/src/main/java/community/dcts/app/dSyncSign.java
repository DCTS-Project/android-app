package community.dcts.app;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

public class dSyncSign {
    private final File KEY_FILE;
    private final String sigField = "sig";

    public dSyncSign(Context context) {
        this(context, "privatekey.json");
    }

    public dSyncSign(Context context, String keyFile) {
        this.KEY_FILE = new File(context.getFilesDir(), keyFile);
    }

    public Object canonicalize(Object x) {
        try {
            if (x == null || x == JSONObject.NULL) return JSONObject.NULL;

            if (x instanceof JSONArray) {
                JSONArray arr = (JSONArray) x;
                JSONArray out = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    out.put(canonicalize(arr.opt(i)));
                }
                return out;
            }

            if (x instanceof JSONObject) {
                JSONObject obj = (JSONObject) x;
                ArrayList<String> keys = new ArrayList<>();
                Iterator<String> it = obj.keys();
                while (it.hasNext()) keys.add(it.next());
                Collections.sort(keys);

                JSONObject out = new JSONObject();
                for (String k : keys) {
                    out.put(k, canonicalize(obj.opt(k)));
                }
                return out;
            }

            return x;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String stableStringify(Object obj) {
        return stableStringifyValue(canonicalize(obj));
    }

    private String quoteString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private String stableStringifyValue(Object value) {
        try {
            if (value == null || value == JSONObject.NULL) return "null";

            if (value instanceof JSONObject) {
                JSONObject o = (JSONObject) value;
                ArrayList<String> keys = new ArrayList<>();
                Iterator<String> it = o.keys();
                while (it.hasNext()) keys.add(it.next());
                Collections.sort(keys);

                StringBuilder sb = new StringBuilder();
                sb.append("{");

                boolean first = true;
                for (String k : keys) {
                    if (!first) sb.append(",");
                    first = false;

                    sb.append(quoteString(k));
                    sb.append(":");
                    sb.append(stableStringifyValue(o.opt(k)));
                }

                sb.append("}");
                return sb.toString();
            }

            if (value instanceof JSONArray) {
                JSONArray arr = (JSONArray) value;
                StringBuilder sb = new StringBuilder();
                sb.append("[");

                for (int i = 0; i < arr.length(); i++) {
                    if (i > 0) sb.append(",");
                    sb.append(stableStringifyValue(arr.opt(i)));
                }

                sb.append("]");
                return sb.toString();
            }

            if (value instanceof String) {
                return quoteString((String) value);
            }

            if (value instanceof Boolean || value instanceof Number) {
                return String.valueOf(value);
            }

            return quoteString(String.valueOf(value));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String normalizePublicKey(String key) {
        if (key == null) return null;

        key = String.valueOf(key)
                .replaceAll("(?i)&lt;br\\s*/?&gt;", "\n")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim();

        if (key.contains("BEGIN PUBLIC KEY") || key.contains("BEGIN RSA PUBLIC KEY")) {
            key = key
                    .replaceAll("\\n+", "\n")
                    .replaceAll("-----BEGIN PUBLIC KEY-----\\s*", "-----BEGIN PUBLIC KEY-----\n")
                    .replaceAll("-----END PUBLIC KEY-----", "\n-----END PUBLIC KEY-----")
                    .replaceAll("-----BEGIN RSA PUBLIC KEY-----\\s*", "-----BEGIN RSA PUBLIC KEY-----\n")
                    .replaceAll("-----END RSA PUBLIC KEY-----", "\n-----END RSA PUBLIC KEY-----");

            boolean isRsa = key.contains("BEGIN RSA PUBLIC KEY");
            String begin = isRsa ? "-----BEGIN RSA PUBLIC KEY-----" : "-----BEGIN PUBLIC KEY-----";
            String end = isRsa ? "-----END RSA PUBLIC KEY-----" : "-----END PUBLIC KEY-----";

            String body = key
                    .replace(begin, "")
                    .replace(end, "")
                    .replaceAll("\\s+", "");

            StringBuilder wrapped = new StringBuilder();
            for (int i = 0; i < body.length(); i += 64) {
                int to = Math.min(i + 64, body.length());
                if (wrapped.length() > 0) wrapped.append('\n');
                wrapped.append(body, i, to);
            }

            return begin + "\n" + wrapped + "\n" + end;
        }

        return key;
    }

    public JSONObject ensureKeyPair() {
        try {
            if (KEY_FILE.exists()) {
                String raw = readFile(KEY_FILE);
                JSONObject json = new JSONObject(raw);
                String privateKey = json.getString("privateKey");

                parsePrivateKey(privateKey);

                return new JSONObject()
                        .put("privateKey", privateKey)
                        .put("publicKey", derivePublicKeyPem(privateKey));
            }
        } catch (Exception ignored) {
        }

        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();

            String privateKey = exportPrivateKeyPem(pair.getPrivate());
            String publicKey = exportPublicKeyPem(pair.getPublic());

            JSONObject save = new JSONObject().put("privateKey", privateKey);
            writeFile(KEY_FILE, save.toString(2));

            return new JSONObject()
                    .put("privateKey", privateKey)
                    .put("publicKey", publicKey);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String signString(String text) {
        try {
            PrivateKey priv = getPrivateKey();
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(priv);
            signer.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(signer.sign(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean verifyString(String text, String signatureBase64, String publicKeyPem) {
        try {
            publicKeyPem = normalizePublicKey(publicKeyPem);

            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(parsePublicKey(publicKeyPem));
            verifier.update(text.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.decode(signatureBase64, Base64.DEFAULT));
        } catch (Exception e) {
            return false;
        }
    }

    public String generateGid(String publicKey) {
        if (publicKey.length() >= 120) {
            return encodeToBase64(publicKey.substring(80, 120));
        } else {
            return encodeToBase64(publicKey.substring(0, publicKey.length()));
        }
    }

    public String encodeToBase64(String str) {
        return Base64.encodeToString(str.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    public PrivateKey getPrivateKey() {
        try {
            return parsePrivateKey(ensureKeyPair().getString("privateKey"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String getPublicKey() {
        try {
            return ensureKeyPair().getString("publicKey");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public JSONObject encrypt(Object data, String recipient) {
        try {
            String plaintext = data instanceof String ? (String) data : stableStringify(data);

            if (recipient != null) {
                recipient = normalizePublicKey(recipient);
            }

            byte[] aesKey;
            JSONObject envelope = new JSONObject().put("method", "");

            if (recipient != null && (recipient.contains("BEGIN PUBLIC KEY") || recipient.contains("BEGIN RSA PUBLIC KEY"))) {
                aesKey = randomBytes(32);
                envelope.put("method", "rsa");

                Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
                OAEPParameterSpec oaep = new OAEPParameterSpec(
                        "SHA-1",
                        "MGF1",
                        MGF1ParameterSpec.SHA1,
                        PSource.PSpecified.DEFAULT
                );
                rsa.init(Cipher.ENCRYPT_MODE, parsePublicKey(recipient), oaep);

                envelope.put("encKey", Base64.encodeToString(rsa.doFinal(aesKey), Base64.NO_WRAP));
            } else {
                byte[] salt = randomBytes(16);
                aesKey = pbkdf2(recipient.toCharArray(), salt, 100000, 32);
                envelope.put("method", "password");
                envelope.put("salt", Base64.encodeToString(salt, Base64.NO_WRAP));
            }

            byte[] iv = randomBytes(12);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] full = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            int tagLength = 16;
            int cipherLength = full.length - tagLength;

            byte[] ciphertext = new byte[cipherLength];
            byte[] tag = new byte[tagLength];

            System.arraycopy(full, 0, ciphertext, 0, cipherLength);
            System.arraycopy(full, cipherLength, tag, 0, tagLength);

            return new JSONObject()
                    .put("method", envelope.getString("method"))
                    .put("encKey", envelope.optString("encKey", null))
                    .put("salt", envelope.optString("salt", null))
                    .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
                    .put("tag", Base64.encodeToString(tag, Base64.NO_WRAP))
                    .put("ciphertext", Base64.encodeToString(ciphertext, Base64.NO_WRAP));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object decrypt(JSONObject envelope, String password) {
        try {
            byte[] aesKey;
            String method = envelope.getString("method");

            if ("rsa".equals(method)) {
                Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
                OAEPParameterSpec oaep = new OAEPParameterSpec(
                        "SHA-1",
                        "MGF1",
                        MGF1ParameterSpec.SHA1,
                        PSource.PSpecified.DEFAULT
                );
                rsa.init(Cipher.DECRYPT_MODE, getPrivateKey(), oaep);
                aesKey = rsa.doFinal(Base64.decode(envelope.getString("encKey"), Base64.DEFAULT));
            } else if ("password".equals(method)) {
                if (password == null) throw new RuntimeException("Password required for password-based decryption");
                aesKey = pbkdf2(
                        password.toCharArray(),
                        Base64.decode(envelope.getString("salt"), Base64.DEFAULT),
                        100000,
                        32
                );
            } else {
                throw new RuntimeException("Unsupported envelope method");
            }

            byte[] iv = Base64.decode(envelope.getString("iv"), Base64.DEFAULT);
            byte[] tag = Base64.decode(envelope.getString("tag"), Base64.DEFAULT);
            byte[] ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.DEFAULT);

            byte[] full = new byte[ciphertext.length + tag.length];
            System.arraycopy(ciphertext, 0, full, 0, ciphertext.length);
            System.arraycopy(tag, 0, full, ciphertext.length, tag.length);

            Cipher decipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(128, iv);
            decipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            String txt = new String(decipher.doFinal(full), StandardCharsets.UTF_8);

            try {
                String trimmed = txt.trim();
                if (trimmed.startsWith("{")) return new JSONObject(trimmed);
                if (trimmed.startsWith("[")) return new JSONArray(trimmed);
                return txt;
            } catch (Exception ignored) {
                return txt;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public String signData(Object data) {
        try {
            PrivateKey priv = getPrivateKey();
            Signature signer = Signature.getInstance("SHA256withRSA");
            String payload = data instanceof String ? (String) data : stableStringify(data);

            signer.initSign(priv);
            signer.update(payload.getBytes(StandardCharsets.UTF_8));

            return Base64.encodeToString(signer.sign(), Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public boolean verifyData(Object data, String signature, String publicKey) {
        try {
            publicKey = normalizePublicKey(publicKey);

            Signature verifier = Signature.getInstance("SHA256withRSA");
            String payload = data instanceof String ? (String) data : stableStringify(data);

            verifier.initVerify(parsePublicKey(publicKey));
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));

            return verifier.verify(Base64.decode(signature, Base64.DEFAULT));
        } catch (Exception e) {
            return false;
        }
    }

    public Object getByPath(Object root, String path) {
        if (path == null || path.isEmpty()) return root;

        Pattern re = Pattern.compile("([^\\.\\[\\]]+)|\\[(\\d+)]");
        Matcher m = re.matcher(path);
        ArrayList<Object> parts = new ArrayList<>();

        while (m.find()) {
            if (m.group(1) != null) parts.add(m.group(1));
            else parts.add(Integer.parseInt(m.group(2)));
        }

        Object cur = root;

        for (Object p : parts) {
            if (cur == null || cur == JSONObject.NULL) return null;

            if (p instanceof String) {
                if (!(cur instanceof JSONObject)) return null;
                cur = ((JSONObject) cur).opt((String) p);
            } else {
                if (!(cur instanceof JSONArray)) return null;
                int index = (Integer) p;
                JSONArray arr = (JSONArray) cur;
                if (index < 0 || index >= arr.length()) return null;
                cur = arr.opt(index);
            }
        }

        return cur;
    }

    public Object cloneWithoutSig(Object obj) {
        try {
            if (obj == null || obj == JSONObject.NULL) return obj;

            Object copy;
            if (obj instanceof JSONObject) {
                copy = new JSONObject(obj.toString());
            } else if (obj instanceof JSONArray) {
                copy = new JSONArray(obj.toString());
            } else {
                return obj;
            }

            if (copy instanceof JSONObject) {
                ((JSONObject) copy).remove(sigField);
            }

            return copy;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object signJson(Object targetOrRoot, String path) {
        try {
            Object target = path != null ? getByPath(targetOrRoot, path) : targetOrRoot;

            if (target == null || target == JSONObject.NULL) {
                if (path != null) return false;
                throw new RuntimeException("target required");
            }

            if (target instanceof JSONArray) {
                JSONArray input = (JSONArray) target;
                JSONArray out = new JSONArray();

                for (int i = 0; i < input.length(); i++) {
                    Object item = input.opt(i);

                    if (!(item instanceof JSONObject)) {
                        out.put(JSONObject.NULL);
                        continue;
                    }

                    JSONObject obj = (JSONObject) item;

                    if (obj.has(sigField)) {
                        out.put(obj.opt(sigField));
                        continue;
                    }

                    Object payload = cloneWithoutSig(obj);
                    String s = signData(payload);

                    obj.put(sigField, s);
                    out.put(s);
                }

                return out;
            }

            if (target instanceof JSONObject) {
                JSONObject obj = (JSONObject) target;

                if (obj.has(sigField)) return obj.opt(sigField);

                Object payload = cloneWithoutSig(obj);
                String s = signData(payload);

                obj.put(sigField, s);
                return s;
            }

            throw new RuntimeException("target must be object or array");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Object verifyJson(Object targetOrRoot, Object publicKeyOrGetter, String path) {
        try {
            Object target = path != null ? getByPath(targetOrRoot, path) : targetOrRoot;

            if (target == null || target == JSONObject.NULL) {
                if (path != null) return false;
                throw new RuntimeException("target required");
            }

            if (target instanceof JSONArray) {
                JSONArray input = (JSONArray) target;
                JSONArray out = new JSONArray();

                for (int i = 0; i < input.length(); i++) {
                    Object item = input.opt(i);

                    if (!(item instanceof JSONObject)) {
                        out.put(false);
                        continue;
                    }

                    JSONObject obj = (JSONObject) item;

                    if (!obj.has(sigField)) {
                        out.put(false);
                        continue;
                    }

                    String signature = obj.optString(sigField, null);
                    String pub = resolvePublicKey(publicKeyOrGetter, obj, targetOrRoot);

                    if (pub == null) {
                        out.put(false);
                        continue;
                    }

                    Object payload = cloneWithoutSig(obj);
                    out.put(verifyData(payload, signature, pub));
                }

                return out;
            }

            if (target instanceof JSONObject) {
                JSONObject obj = (JSONObject) target;

                if (!obj.has(sigField)) return false;

                String signature = obj.optString(sigField, null);
                String pub = resolvePublicKey(publicKeyOrGetter, obj, targetOrRoot);

                if (pub == null) return false;

                Object payload = cloneWithoutSig(obj);
                return verifyData(payload, signature, pub);
            }

            throw new RuntimeException("target must be object or array");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String resolvePublicKey(Object publicKeyOrGetter, JSONObject target, Object root) {
        if (publicKeyOrGetter == null) return null;
        if (publicKeyOrGetter instanceof String) return (String) publicKeyOrGetter;
        return null;
    }

    private byte[] randomBytes(int len) {
        byte[] out = new byte[len];
        new java.security.SecureRandom().nextBytes(out);
        return out;
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLenBytes) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLenBytes * 8);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return factory.generateSecret(spec).getEncoded();
    }

    private String derivePublicKeyPem(String privateKeyPem) throws Exception {
        PrivateKey privateKey = parsePrivateKey(privateKeyPem);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        PKCS8EncodedKeySpec privSpec = new PKCS8EncodedKeySpec(privateKey.getEncoded());
        PrivateKey priv = keyFactory.generatePrivate(privSpec);

        java.security.interfaces.RSAPrivateCrtKey crtKey = (java.security.interfaces.RSAPrivateCrtKey) priv;
        java.security.spec.RSAPublicKeySpec pubSpec = new java.security.spec.RSAPublicKeySpec(
                crtKey.getModulus(),
                crtKey.getPublicExponent()
        );
        PublicKey publicKey = keyFactory.generatePublic(pubSpec);
        return exportPublicKeyPem(publicKey);
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        byte[] der = parsePemBody(pem);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private PublicKey parsePublicKey(String pem) throws Exception {
        pem = normalizePublicKey(pem);
        byte[] der = parsePemBody(pem);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        return KeyFactory.getInstance("RSA").generatePublic(spec);
    }

    private byte[] parsePemBody(String pem) {
        String clean = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                .replace("-----END RSA PUBLIC KEY-----", "")
                .replaceAll("\\s+", "");
        return Base64.decode(clean, Base64.DEFAULT);
    }

    private String exportPrivateKeyPem(PrivateKey privateKey) {
        String b64 = Base64.encodeToString(privateKey.getEncoded(), Base64.NO_WRAP);
        return "-----BEGIN PRIVATE KEY-----\n" + wrap64(b64) + "\n-----END PRIVATE KEY-----";
    }

    private String exportPublicKeyPem(PublicKey publicKey) {
        String b64 = Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP);
        return "-----BEGIN PUBLIC KEY-----\n" + wrap64(b64) + "\n-----END PUBLIC KEY-----";
    }

    private String wrap64(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i += 64) {
            int end = Math.min(i + 64, s.length());
            if (out.length() > 0) out.append('\n');
            out.append(s, i, end);
        }
        return out.toString();
    }

    private String readFile(File file) throws Exception {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();

        byte[] buffer = new byte[4096];
        int read;

        while ((read = fis.read(buffer)) != -1) {
            bos.write(buffer, 0, read);
        }

        fis.close();
        return bos.toString("UTF-8");
    }

    private void writeFile(File file, String content) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
        fos.write(content.getBytes(StandardCharsets.UTF_8));
        fos.flush();
        fos.close();
    }
}