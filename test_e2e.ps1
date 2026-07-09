param(
    [string]$LogFilePath
)

Write-Host "Esperando a que la aplicacion inicie..."
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:8080/api/mesas" -Method Get -ErrorAction Stop
        if ($response.StatusCode -eq 200) {
            $ready = $true
            break
        }
    } catch {
        Start-Sleep -Seconds 2
    }
}

if (-not $ready) {
    Write-Host "La aplicacion no inicio a tiempo."
    exit 1
}

Write-Host "Aplicacion iniciada. Creando mesa..."
$mesa = Invoke-RestMethod -Uri "http://localhost:8080/api/mesas" -Method Post -ContentType "application/json" -Body '{"numeroDeMesa": 100, "capacidad": 4}'
$mesaId = $mesa.id
Write-Host "Mesa creada con ID: $mesaId"

Start-Sleep -Seconds 2

Write-Host "Buscando token en el archivo de log..."
$tokenLine = Select-String -Path $LogFilePath -Pattern "=== TEST INFO === Token de sesion generado para la mesa 100: (.*)"
if (-not $tokenLine) {
    Write-Host "No se encontro el token en el log."
    exit 1
}
$token = $tokenLine.Matches[0].Groups[1].Value
Write-Host "Token obtenido: $token"

$headers = @{
    "X-Session-Token" = $token
}

Write-Host "Abriendo pedido para la mesa..."
Invoke-RestMethod -Uri "http://localhost:8080/api/pedidos/mesa/$mesaId" -Method Post -Headers $headers -ErrorAction Stop | Out-Null

Write-Host "Agregando Hamburguesa (ID 1) al pedido..."
$itemBody = @{
    "platilloId" = 1
    "cantidad" = 2
    "notas" = "Sin cebolla"
} | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:8080/api/pedidos/mesa/$mesaId/items" -Method Post -Headers $headers -ContentType "application/json" -Body $itemBody -ErrorAction Stop | Out-Null

Write-Host "Agregando Refresco (ID 3) al pedido..."
$itemBody2 = @{
    "platilloId" = 3
    "cantidad" = 2
    "notas" = "Frio"
} | ConvertTo-Json
Invoke-RestMethod -Uri "http://localhost:8080/api/pedidos/mesa/$mesaId/items" -Method Post -Headers $headers -ContentType "application/json" -Body $itemBody2 -ErrorAction Stop | Out-Null

Write-Host "Obteniendo resumen del pedido..."
$resumen = Invoke-RestMethod -Uri "http://localhost:8080/api/pedidos/mesa/$mesaId" -Method Get -Headers $headers -ErrorAction Stop
Write-Host "Resumen obtenido:"
Write-Host " - Subtotal: $($resumen.subtotal)"
Write-Host " - IVA (16%): $($resumen.iva)"
Write-Host " - Propina (10%): $($resumen.propina)"
Write-Host " - TOTAL: $($resumen.total)"

Write-Host "Procesando pago en EFECTIVO..."
$pagoBody = @{
    "metodo" = "EFECTIVO"
} | ConvertTo-Json
$pago = Invoke-RestMethod -Uri "http://localhost:8080/api/pagos/mesa/$mesaId" -Method Post -Headers $headers -ContentType "application/json" -Body $pagoBody -ErrorAction Stop
$pagoId = $pago.id
Write-Host "Pago registrado con ID: $pagoId en estado $($pago.estado)"

Write-Host "Confirmando pago en efectivo..."
Invoke-RestMethod -Uri "http://localhost:8080/api/pagos/$pagoId/confirmar-efectivo" -Method Post -ErrorAction Stop | Out-Null

Write-Host "Verificando que el estado de la mesa haya vuelto a DISPONIBLE..."
$mesaFinal = Invoke-RestMethod -Uri "http://localhost:8080/api/mesas/$mesaId" -Method Get -ErrorAction Stop
Write-Host "Estado final de la mesa: $($mesaFinal.estado)"

if ($mesaFinal.estado -eq "DISPONIBLE") {
    Write-Host "FLUJO COMPLETO FINALIZADO CON EXITO!"
} else {
    Write-Host "ERROR: La mesa no quedo disponible."
}
