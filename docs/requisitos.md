# Requisitos cubiertos

| ID | Requisito | Implementación |
|---|---|---|
| RF-01 | Registrar, consultar y modificar productos con clave, nombre, descripción, unidad, equivalencia de empaque, categoría y estado. | Combo de unidades y equivalencia por empaque; persistencia en `Productos`. |
| RF-02 | Registrar entradas con fecha, producto, cantidad y costo por unidad base; convertir empaques a piezas. | `InventoryService.registerEntry` normaliza cantidades a la unidad base antes de guardar. |
| RF-03 | Registrar salidas con fecha, producto y cantidad; convertir empaques a piezas y calcular su costo. | `InventoryService.registerExit` normaliza cantidades y el método se elige al registrar. |
| RF-04 | Consultar PEPS y promedio ponderado sobre los mismos movimientos. | `InventoryCostingService`; los costos de salida no se almacenan ni modifican el historial. |
| RF-05 | Consultar existencias, costo unitario, valor e historial. | Opciones 6 y 7 del menú. |
| RF-06 | Consultar el kardex de un producto con compras, ventas, ajustes y saldos corridos por promedio ponderado, filtrando por categoría y producto. | Pestaña **Kardex promedio** de la interfaz gráfica. |
| RF-07 | Autenticar usuarios en GUI y consola. El administrador puede gestionar productos, fechas de movimientos, ajustes de inventario y cuentas; el trabajador solo consulta y registra entradas/salidas con fecha UTC automática. | Tabla `usuarios`; permisos por rol en `InventoryFrame`, `ConsoleApp` y `InventoryService`; historial de movimientos inmutable. |
| RF-08 | Permitir que cada usuario cambie su nombre de acceso y contraseña, y guarde su preferencia de modo noche. | Configuración de usuario en GUI y consola; confirmación de contraseña actual; preferencia persistida en `usuarios`. |
| RF-09 | Definir la cantidad de piezas por caja, paquete, bolsa o docena; mostrar inventario e historial en unidad base. | `PiezasPorEmpaque` en `productos`; conversión de cantidades a pza en `InventoryService`. |
| RF-10 | Ajustar existencias mediante conteo físico, guardando diferencia, motivo, usuario y fecha; valorar aumentos al costo promedio vigente o pedir costo si no hay existencias. | Movimientos de ajuste (+/-), bloqueo transaccional por producto y columnas `UsuarioId`/`Motivo` en `movimientos`. |
| RF-11 | Confirmar antes de guardar ajustes o desactivar productos. | Confirmaciones en GUI/consola. El resumen mantiene el valor PEPS y productos activos sin existencia. |
| RNF-01 | Cantidades y costos consistentes. | Validación decimal, restricciones SQL y cálculo con `BigDecimal`. Salidas guardan `CostoUnitario=0` porque el esquema lo exige; su costo real se calcula por método desde el historial. |
| RNF-02 | Persistencia entre ejecuciones. | PostgreSQL de Supabase vía JDBC. |
| RNF-03 | Rechazar datos incorrectos con mensajes comprensibles. | Validaciones del servicio y mensajes de consola. |
| RNF-04 | Evitar actualizaciones parciales. | Transacción JDBC por movimiento y bloqueo de fila del producto. |
| RNF-05 | Responsabilidades separadas. | Paquetes `console`, `model`, `persistence`, `service` y `exception`. |
| RNF-06 | Relacionar requisitos y pruebas. | Matriz de trazabilidad en `casos-de-prueba.md`. |

## Decisiones y alcance

- Aplicación de escritorio con interfaz gráfica y modo consola; base de datos PostgreSQL alojada en Supabase.
- La conexión es directa por JDBC, con TLS; credenciales obtenidas del entorno.
- Los movimientos confirmados son inmutables. Para corregir existencias se registra un ajuste nuevo con conteo físico y motivo; el registro original se conserva.
- Solo administradores pueden ajustar inventario. Los aumentos usan el costo promedio ponderado vigente; si no hay existencias se exige un costo por unidad.
- Las entradas, salidas y ajustes nuevos guardan el identificador del usuario. Los ajustes requieren motivo; los movimientos históricos se conservan sin autor/motivo cuando esos datos no existían al migrar.
- Los costos de salida se recalculan para el método elegido y no se guardan como una segunda versión de la transacción.
- Las cuentas de la aplicación se guardan en Supabase; el primer inicio crea el primer administrador. La contraseña PostgreSQL sigue siendo necesaria para la conexión y se configura en el entorno; los roles se hacen cumplir en la interfaz gráfica y en el modo consola, no en la base de datos.
- Los movimientos registrados son de solo lectura: ningún rol puede editarlos o eliminarlos. Solo el administrador puede seleccionar la fecha al registrar un movimiento; al trabajador se le asigna la fecha actual UTC.
- El estado inactivo bloquea nuevos movimientos, pero mantiene disponibles las consultas e historial.
- No se incluyen compras, ventas comerciales, facturación ni proveedores.
