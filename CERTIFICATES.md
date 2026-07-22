# Certificate Configuration for AI Gateway

This document explains how to configure SSL/TLS certificates for connecting to the company AI gateway.

## Problem

When connecting to the internal AI gateway at `https://aig.example.com`, you may encounter SSL certificate validation errors:

```
javax.net.ssl.SSLHandshakeException: PKIX path building failed: 
sun.security.provider.certpath.SunCertPathBuilderException: 
unable to find valid certification path to requested target
```

This occurs because the gateway uses certificates issued by the company's internal Certificate Authority (CA), which is not in Java's default truststore.

## Solution Overview

The application supports loading a custom truststore containing company certificates. This approach:

- ✅ Keeps certificates **outside version control** (security best practice)
- ✅ Works per-project without modifying the global Java installation
- ✅ Can be configured differently per developer/environment
- ✅ Is easy to update when certificates are renewed

## Setup Instructions

### Step 1: Obtain Company Certificates

**Option A: Export from Browser (Recommended for most users)**

1. Open the AI gateway URL in your browser: `https://aig.example.com`
2. Click the padlock icon → View certificate
3. Navigate to the certificate chain and export:
   - Root CA certificate (e.g., "Company Root CA")
   - Any intermediate CA certificates
4. Save as `.crt` or `.cer` files

**Option B: From IT Department**

Contact your IT security team and request:
- Company root CA certificate
- Any intermediate CA certificates
- File format: DER (`.cer`) or PEM (`.crt`)

**Option C: Use `openssl` (for advanced users)**

```bash
# Retrieve certificate chain
openssl s_client -showcerts -connect aig.example.com:443 </dev/null 2>/dev/null | \
  openssl x509 -outform PEM > company-cert.pem
```

### Step 2: Create a Custom Truststore

Create a Java KeyStore (JKS) file containing the company certificates:

```bash
# Create the certs directory (gitignored)
mkdir certs

# Import the root CA certificate
keytool -import -trustcacerts -alias company-root-ca \
  -file company-root-ca.crt \
  -keystore certs/company-truststore.jks \
  -storepass changeit

# If you have intermediate certificates, add them too
keytool -import -trustcacerts -alias company-intermediate-ca \
  -file company-intermediate-ca.crt \
  -keystore certs/company-truststore.jks \
  -storepass changeit
```

**Important Notes:**
- Use a strong password in production (not `changeit`)
- The `certs/` directory is gitignored and will not be committed
- You can use a different path/filename if preferred

### Step 3: Configure the Application

Add SSL configuration to your **`application-remote.properties`** file (also gitignored):

```properties
# AI Gateway authentication (existing config)
gateway.auth.token-url=https://your-keycloak.com/realms/your-realm/protocol/openid-connect/token
gateway.auth.client-id=your-client-id
gateway.auth.client-secret=your-secret
gateway.auth.scope=openid

# SSL/TLS certificate configuration (add this)
gateway.auth.ssl.trust-store-path=certs/company-truststore.jks
gateway.auth.ssl.trust-store-password=changeit
gateway.auth.ssl.trust-store-type=JKS
```

**Configuration Options:**

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `trust-store-path` | Yes | - | Path to truststore file (absolute or relative to working directory) |
| `trust-store-password` | No | `null` | Password for the truststore file |
| `trust-store-type` | No | `JKS` | Truststore format: `JKS`, `PKCS12`, etc. |

### Step 4: Verify the Configuration

Run the application and check the logs:

```bash
mvn spring-boot:run
```

You should see:
```
INFO  GatewayAuthConfig - Custom SSL context configured with truststore: certs/company-truststore.jks
```

If the truststore is not found, you'll see:
```
ERROR - Truststore file not found: /absolute/path/to/certs/company-truststore.jks
        Please ensure the company certificates are installed (see CERTIFICATES.md)
```

## Troubleshooting

### Error: "Truststore file not found"

**Cause:** The path in `trust-store-path` is incorrect.

**Solution:**
- Check the path is correct relative to the project root
- Use absolute path if needed: `C:/path/to/certs/company-truststore.jks` (Windows)
- Verify the file exists: `Test-Path certs/company-truststore.jks` (PowerShell)

### Error: "Keystore was tampered with, or password was incorrect"

**Cause:** The `trust-store-password` doesn't match the password used when creating the truststore.

**Solution:**
- Verify the password in `application-remote.properties`
- Recreate the truststore with the correct password

### Error: "Invalid keystore format"

**Cause:** The `trust-store-type` doesn't match the actual file format.

**Solution:**
- If you created a `.p12` file, set `trust-store-type=PKCS12`
- For `.jks` files, use `trust-store-type=JKS` (default)

### Certificate still not trusted

**Cause:** You may have imported the wrong certificate or the certificate chain is incomplete.

**Solution:**

1. Verify which certificates are in your truststore:
   ```bash
   keytool -list -v -keystore certs/company-truststore.jks -storepass changeit
   ```

2. Compare with the actual certificate chain from the server:
   ```bash
   openssl s_client -showcerts -connect aig.example.com:443 </dev/null
   ```

3. Ensure you have both root and intermediate certificates if required.

## Alternative: System-Wide Certificate Installation

If you prefer to install certificates system-wide (affects all Java applications):

**Windows:**
```powershell
# Import into Java's default cacerts (requires admin)
& "$env:JAVA_HOME\bin\keytool" -import -trustcacerts -alias company-ca `
  -file company-root-ca.crt `
  -keystore "$env:JAVA_HOME\lib\security\cacerts" `
  -storepass changeit
```

**Linux/macOS:**
```bash
# Import into Java's default cacerts (requires sudo)
sudo keytool -import -trustcacerts -alias company-ca \
  -file company-root-ca.crt \
  -keystore $JAVA_HOME/lib/security/cacerts \
  -storepass changeit
```

**Trade-offs:**
- ✅ Affects all Java applications (no per-project config needed)
- ❌ Requires admin/root access
- ❌ Not portable across environments
- ❌ Harder to track/document in project

## Security Best Practices

1. **Never commit certificates to version control**
   - The `.gitignore` is configured to exclude `certs/` and `*.jks`
   - Verify with: `git status --ignored`

2. **Use strong passwords**
   - Default `changeit` is fine for local development
   - Use strong passwords for shared environments

3. **Keep certificates up to date**
   - Company certificates expire (typically every 1-5 years)
   - Re-import renewed certificates when notified by IT

4. **Document your specific setup**
   - If your team uses a different certificate source, document it
   - Consider creating a team-specific setup script

## For CI/CD Environments

In automated build/test environments:

1. Store the truststore as a **secret file** in your CI system
2. Inject it at build time (e.g., write to `certs/` directory)
3. Configure via environment variables:

```bash
export GATEWAY_AUTH_SSL_TRUST_STORE_PATH=/path/to/truststore.jks
export GATEWAY_AUTH_SSL_TRUST_STORE_PASSWORD=${TRUSTSTORE_PASSWORD}
```

Spring Boot will automatically map these to the properties.

## Further Reading

- [Java Keytool Documentation](https://docs.oracle.com/en/java/javase/17/docs/specs/man/keytool.html)
- [Understanding SSL Certificate Chains](https://en.wikipedia.org/wiki/Chain_of_trust)
- [Spring Boot SSL Configuration](https://docs.spring.io/spring-boot/reference/features/ssl.html)
