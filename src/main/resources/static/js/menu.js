/**
 * Lógica del menú interactivo de MesaQR.
 * Maneja la interacción con la API de pedidos, pagos y WebSocket.
 */

function encabezadosApi() {
    return {
        'Content-Type': 'application/json',
        'X-Session-Token': token
    };
}

async function agregarElemento(platilloId, nombre) {
    mostrarNotificacion('Agregando ' + nombre + '...');
    const resp = await fetch('/api/pedidos/mesa/' + mesaId + '/items', {
        method: 'POST',
        headers: encabezadosApi(),
        body: JSON.stringify({ platilloId, cantidad: 1 })
    });
    if (resp.ok) {
        mostrarNotificacion(nombre + ' agregado');
        setTimeout(() => location.reload(), 500);
    } else {
        const err = await resp.json();
        mostrarNotificacion('Error: ' + err.mensaje);
    }
}

async function pagar(metodo) {
    if (!confirm('¿Confirmar pago por ' + document.querySelector('.monto').textContent + '?')) return;

    mostrarNotificacion('Procesando pago...');
    const resp = await fetch('/api/pagos/mesa/' + mesaId, {
        method: 'POST',
        headers: encabezadosApi(),
        body: JSON.stringify({ metodo, tokenProveedor: 'tok_simulado' })
    });
    if (!resp.ok) {
        const err = await resp.json();
        mostrarNotificacion('Error: ' + (err.mensaje || 'No se pudo procesar el pago'));
        return;
    }
    const data = await resp.json();

    if (data.estado === 'COMPLETADO') {
        mostrarNotificacion('¡Pago exitoso! Gracias.');
        setTimeout(() => location.reload(), 2000);
    } else if (data.urlRedireccion) {
        mostrarNotificacion(data.mensaje);
        setTimeout(() => location.reload(), 3000);
    } else {
        mostrarNotificacion(data.mensaje);
        setTimeout(() => location.reload(), 2000);
    }
}

async function cancelarPedido() {
    if (!confirm('¿Seguro que quieres cancelar el pedido?')) return;

    mostrarNotificacion('Cancelando pedido...');
    const resp = await fetch('/api/pedidos/mesa/' + mesaId + '/cancelar', {
        method: 'PUT',
        headers: encabezadosApi()
    });
    if (resp.ok) {
        mostrarNotificacion('Pedido cancelado');
        setTimeout(() => location.reload(), 1500);
    } else {
        const err = await resp.json();
        mostrarNotificacion('Error: ' + (err.mensaje || 'No se pudo cancelar'));
    }
}

function conectarCanalWeb() {
    const socket = new SockJS('/ws');
    const cliente = Stomp.over(socket);
    cliente.connect({'X-Session-Token': token}, () => {
        cliente.subscribe('/topic/mesas', () => location.reload());
    });
}

function mostrarNotificacion(msg) {
    const t = document.getElementById('notificacion');
    t.textContent = msg;
    t.classList.add('visible');
    setTimeout(() => t.classList.remove('visible'), 2500);
}

conectarCanalWeb();
