# PowerShell script to export SSL certificate from the AI gateway
# This script extracts the certificate chain and saves it to a file

param(
    [string]$Hostname = "aig.example.com",
    [int]$Port = 443,
    [string]$OutputPath = "certs"
)

Write-Host "Connecting to $Hostname`:$Port to retrieve certificate..." -ForegroundColor Cyan

try {
    # Create TCP connection
    $tcpClient = New-Object System.Net.Sockets.TcpClient
    $tcpClient.Connect($Hostname, $Port)
    
    # Create SSL stream
    $sslStream = New-Object System.Net.Security.SslStream(
        $tcpClient.GetStream(),
        $false,
        # Certificate validation callback - accept all to retrieve the cert
        { param($sender, $certificate, $chain, $sslPolicyErrors) 
            $script:remoteCert = $certificate
            $script:certChain = $chain
            return $true  # Accept certificate to retrieve it
        }
    )
    
    # Initiate SSL handshake
    $sslStream.AuthenticateAsClient($Hostname)
    
    # Create output directory
    if (-not (Test-Path $OutputPath)) {
        New-Item -ItemType Directory -Path $OutputPath -Force | Out-Null
        Write-Host "Created directory: $OutputPath" -ForegroundColor Green
    }
    
    # Export the server certificate
    $certBytes = $remoteCert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert)
    $serverCertPath = Join-Path $OutputPath "server-cert.cer"
    [System.IO.File]::WriteAllBytes($serverCertPath, $certBytes)
    Write-Host "✓ Saved server certificate to: $serverCertPath" -ForegroundColor Green
    
    # Display certificate info
    Write-Host "`nServer Certificate Details:" -ForegroundColor Yellow
    Write-Host "  Subject:  $($remoteCert.Subject)"
    Write-Host "  Issuer:   $($remoteCert.Issuer)"
    Write-Host "  Valid:    $($remoteCert.NotBefore) to $($remoteCert.NotAfter)"
    Write-Host "  Thumbprint: $($remoteCert.Thumbprint)"
    
    # Export certificate chain
    if ($certChain -and $certChain.ChainElements.Count -gt 1) {
        Write-Host "`nCertificate Chain ($($certChain.ChainElements.Count) certificates):" -ForegroundColor Yellow
        
        for ($i = 0; $i -lt $certChain.ChainElements.Count; $i++) {
            $chainCert = $certChain.ChainElements[$i].Certificate
            $certType = if ($i -eq 0) { "server" } elseif ($i -eq $certChain.ChainElements.Count - 1) { "root-ca" } else { "intermediate-ca-$i" }
            
            Write-Host "  [$i] $($chainCert.Subject)"
            
            # Export each certificate in the chain
            $chainCertBytes = $chainCert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert)
            $chainCertPath = Join-Path $OutputPath "$certType.cer"
            [System.IO.File]::WriteAllBytes($chainCertPath, $chainCertBytes)
            Write-Host "      Saved to: $chainCertPath" -ForegroundColor Green
        }
    }
    
    Write-Host "`n✓ Certificate export complete!" -ForegroundColor Green
    Write-Host "`nNext steps:" -ForegroundColor Cyan
    Write-Host "  1. Run: .\scripts\create-truststore.ps1" -ForegroundColor White
    Write-Host "  2. Add SSL config to application-local.properties" -ForegroundColor White
    Write-Host "  3. Run: mvn spring-boot:run" -ForegroundColor White
    
} catch {
    Write-Host "Error retrieving certificate: $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "`nIf the connection failed, try exporting the certificate manually from your browser:" -ForegroundColor Yellow
    Write-Host "  1. Open https://$Hostname in your browser"
    Write-Host "  2. Click the padlock icon → View certificate"
    Write-Host "  3. Go to 'Certification Path' and export the root CA"
    Write-Host "  4. Save to the '$OutputPath' directory"
    exit 1
} finally {
    if ($sslStream) { $sslStream.Close() }
    if ($tcpClient) { $tcpClient.Close() }
}
