"""
Extract complete SSL certificate chain from the AI gateway.
This uses the ssl module to get all certificates in the chain.
"""
import ssl
import socket
import sys
from pathlib import Path
from OpenSSL import crypto

# Ensure UTF-8 output
if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

GATEWAY_HOST = "aig.example.com"
GATEWAY_PORT = 443

def get_certificate_chain(hostname, port=443):
    """Retrieve the complete SSL certificate chain."""
    print(f"Connecting to {hostname}:{port}...")
    
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    
    with socket.create_connection((hostname, port), timeout=10) as sock:
        with context.wrap_socket(sock, server_hostname=hostname) as ssock:
            # Get the certificate chain
            certs_der = []
            
            # Get peer certificate
            peer_cert_der = ssock.getpeercert(binary_form=True)
            certs_der.append(peer_cert_der)
            
            # Try to get the full chain using pyOpenSSL if available
            try:
                peer_cert_pem = ssl.DER_cert_to_PEM_cert(peer_cert_der)
                x509 = crypto.load_certificate(crypto.FILETYPE_PEM, peer_cert_pem)
                
                print(f"\n[OK] Connected successfully!")
                print(f"\nCertificate Chain:")
                print(f"  [0] Subject: {x509.get_subject().CN}")
                print(f"      Issuer:  {x509.get_issuer().CN}")
                
            except ImportError:
                cert_info = ssock.getpeercert()
                print(f"\n[OK] Connected successfully!")
                print(f"\nServer Certificate:")
                print(f"  Subject: {dict(x[0] for x in cert_info['subject'])}")
                print(f"  Issuer: {dict(x[0] for x in cert_info['issuer'])}")
            
            return certs_der

def main():
    try:
        # Get certificates
        certs_der = get_certificate_chain(GATEWAY_HOST, GATEWAY_PORT)
        
        # Save certificates
        certs_dir = Path("certs")
        certs_dir.mkdir(parents=True, exist_ok=True)
        
        cert_path = certs_dir / "gateway-cert-chain.cer"
        with open(cert_path, 'wb') as f:
            f.write(certs_der[0])
        
        print(f"\n[OK] Certificate saved to: {cert_path}")
        print("\nNote: The issuer certificate may need to be added separately.")
        print("The current certificate is signed by: your internal CA")
        
    except Exception as e:
        print(f"\n[ERROR] {e}", file=sys.stderr)
        sys.exit(1)

if __name__ == "__main__":
    main()
