<#
.SYNOPSIS
  Print this machine's LAN IPv4 address and the URLs to put in .env.

.DESCRIPTION
  The address is what a phone on the same Wi-Fi must use to reach this laptop,
  so it is the value APP_PUBLIC_BASE_URL needs for the QR code to work.

.EXAMPLE
  ./scripts/lan-url.ps1

.EXAMPLE
  ./scripts/lan-url.ps1 -FrontendPort 4000 -ApiPort 9090
#>

[CmdletBinding()]
param(
    [int]$FrontendPort = 3000,
    [int]$ApiPort = 8080
)

$ErrorActionPreference = 'Stop'

function Get-LanIPv4 {
    # Preferred: the source address the default route would actually use.
    try {
        $candidates = Get-NetIPConfiguration -ErrorAction Stop |
            Where-Object { $null -ne $_.IPv4DefaultGateway -and $null -ne $_.IPv4Address } |
            ForEach-Object { $_.IPv4Address.IPAddress }

        $ip = $candidates | Where-Object { $_ -notlike '127.*' -and $_ -notlike '169.254.*' } | Select-Object -First 1
        if ($ip) { return $ip }
    } catch {
        # Fall through to the next strategy.
    }

    # Fallback: any non-loopback, non-APIPA, non-virtual IPv4 address.
    try {
        $ip = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object {
                $_.IPAddress -notlike '127.*' -and
                $_.IPAddress -notlike '169.254.*' -and
                $_.InterfaceAlias -notmatch 'Loopback|vEthernet|WSL|Docker|Hyper-V'
            } |
            Sort-Object -Property InterfaceMetric |
            Select-Object -First 1 -ExpandProperty IPAddress
        if ($ip) { return $ip }
    } catch {
        # Fall through.
    }

    # Last resort: DNS resolution of the local hostname.
    try {
        $ip = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) |
            Where-Object { $_.AddressFamily -eq 'InterNetwork' -and $_.IPAddressToString -notlike '127.*' } |
            Select-Object -First 1 -ExpandProperty IPAddressToString
        if ($ip) { return $ip }
    } catch {
        # Give up below.
    }

    return $null
}

$ip = Get-LanIPv4

if (-not $ip) {
    Write-Error "Could not determine a LAN IPv4 address automatically. Run 'ipconfig' and use the 192.168.x.x or 10.x.x.x address of your Wi-Fi adapter."
    exit 1
}

Write-Output "LAN IPv4 address : $ip"
Write-Output ""
Write-Output "Put these in your .env:"
Write-Output ""
Write-Output "  APP_PUBLIC_BASE_URL=http://${ip}:${FrontendPort}"
Write-Output "  APP_CORS_ALLOWED_ORIGINS=http://${ip}:${FrontendPort},http://localhost:${FrontendPort}"
Write-Output ""
Write-Output "Contribute URL encoded in the QR code:"
Write-Output ""
Write-Output "  http://${ip}:${FrontendPort}/contribute"
Write-Output ""
Write-Output "API base URL (reachable from phones on the same Wi-Fi):"
Write-Output ""
Write-Output "  http://${ip}:${ApiPort}/api"
Write-Output ""
Write-Output "Check that the laptop and the phones are on the same Wi-Fi network and"
Write-Output "that the firewall allows inbound TCP on ports $FrontendPort and $ApiPort."
