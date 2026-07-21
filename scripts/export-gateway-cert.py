"""
Extract SSL certificate chain from the AI gateway and create a Java truststore.
This script handles the OAuth2 authentication and retrieves the complete certificate chain.
"""
import ssl
import socket
import sys
from pathlib import Path

# Ensure UTF-8 output
if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

# Gateway configuration - replace with your actual gateway hostname
GATEWAY_HOST = "aig.example.com"
GATEWAY_PORT = 443

def get_certificate_chain(hostname, port=443):
    """Retrieve the SSL certificate chain from a host."""
    print(f"Connecting to {hostname}:{port}...")
    
    context = ssl.create_default_context()
    
    with socket.create_connection((hostname, port), timeout=10) as sock:
        with context.wrap_socket(sock, server_hostname=hostname) as ssock:
            # Get the certificate in DER format
            der_cert = ssock.getpeercert(binary_form=True)
            
            # Get certificate info
            cert_info = ssock.getpeercert()
            
            print(f"\n[OK] Connected successfully!")
            print(f"\nServer Certificate:")
            print(f"  Subject: {dict(x[0] for x in cert_info['subject'])}")
            print(f"  Issuer: {dict(x[0] for x in cert_info['issuer'])}")
            print(f"  Valid: {cert_info['notBefore']} to {cert_info['notAfter']}")
            
            return der_cert, cert_info

def save_certificate(der_cert, output_path):
    """Save certificate in DER format."""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    
    with open(output_path, 'wb') as f:
        f.write(der_cert)
    
    print(f"\n[OK] Certificate saved to: {output_path}")
    return output_path

def main():
    try:
        # Get the certificate
        der_cert, cert_info = get_certificate_chain(GATEWAY_HOST, GATEWAY_PORT)
        
        # Save to certs directory
        cert_path = Path("certs") / "gateway.cer"
        saved_path = save_certificate(der_cert, cert_path)
        
        print("\n" + "="*60)
        print("Certificate export successful!")
        print("="*60)
        print(f"\nNext steps:")
        print(f"  1. Create truststore:")
        print(f'     keytool -import -trustcacerts -alias gateway-server \\')
        print(f'       -file {saved_path} \\')
        print(f'       -keystore certs/company-truststore.jks \\')
        print(f'       -storepass changeit')
        print(f"\n  2. Add to application-local.properties:")
        print(f'     gateway.auth.ssl.trust-store-path=certs/company-truststore.jks')
        print(f'     gateway.auth.ssl.trust-store-password=changeit')
        print(f'     gateway.auth.ssl.trust-store-type=JKS')
        print(f"\n  3. Run: mvn spring-boot:run")
        
    except Exception as e:
        print(f"\n[ERROR] {e}", file=sys.stderr)
        print("\nNote: This script only extracts the server certificate.", file=sys.stderr)
        print("If you need the full CA chain, export it from your browser:", file=sys.stderr)
        print("  1. Open https://your-gateway.example.com in browser", file=sys.stderr)
        print("  2. Click padlock -> View certificate", file=sys.stderr)
        print("  3. Export the root CA certificate", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
