# Casos de prueba y trazabilidad

Los casos automatizados se ejecutan con `mvn test`. Los casos de integración usan H2 en modo PostgreSQL porque esta ejecución no dispone de credenciales de Supabase. H2 verifica el acceso JDBC y las transacciones del repositorio, pero no sustituye una prueba de conexión en el proyecto Supabase real.

## Diseño de pruebas

- **Partición de equivalencia:** cantidades y costos positivos son válidos; cero, negativos y valores por encima de la precisión admitida son rechazados. Aplicado a RF-02/RF-03 en `InventoryServiceTest`.
- **Valores límite:** se comprueban cero, negativo y la precisión inmediatamente superior a 4 decimales para cantidades y costos. Aplicado a RNF-01/RNF-03.
- **Escenario:** el historial de referencia se procesa por ambos métodos y se verifica el resultado de costo y valor final. Aplicado a RF-04/RF-05 en `InventoryCostingServiceTest` y el test JDBC.

## Matriz de casos

| ID | Req. | Precondición y entrada | Resultado esperado | Resultado obtenido / estado | Evidencia |
|---|---|---|---|---|---|
| CP-01 | RF-01, RNF-02 | Crear y modificar un producto válido; probar estado inactivo. | Cambios persistidos; producto inactivo rechaza movimientos. | Automatizado: pasa. | `productCanBeUpdatedAndInactiveProductsRejectMovements` |
| CP-02 | RF-01, RNF-03 | Crear dos productos con la misma clave. | Rechazo sin reemplazar el original. | Automatizado: pasa. | `rejectsDuplicateProductKeyAndKeepsTheOriginalProduct` |
| CP-03 | RF-02 | Registrar entradas de 10 a $20 y 10 a $30. | Existencias y valoración reflejan las entradas. | Automatizado como parte del historial de referencia. | `referenceScenarioProducesExpectedFifoAndMovingAverageResults`; prueba JDBC |
| CP-04 | RF-03 | Registrar una salida válida de 5 unidades. | Movimiento persistido; costo calculable según método sin guardarlo en `CostoUnitario`. | Automatizado: el marcador `0` y costo recalculado se verifican por separado. | Pruebas de referencia y JDBC |
| CP-05 | RF-02, RF-03, RNF-03 | Cantidad cero, negativa o con más de 4 decimales. | Rechazo sin persistir el movimiento. | Automatizado: pasa. | `InventoryServiceTest` |
| CP-06 | RF-02, RNF-03 | Costo cero, negativo o con más de 4 decimales. | Rechazo sin persistir la entrada. | Automatizado: pasa. | `InventoryServiceTest` |
| CP-07 | RF-03, RNF-04 | Retirar más unidades que las disponibles. | Rechazo; stock e historial no cambian. | Automatizado en servicio y JDBC: pasa. | `rejectedExitDoesNotAddMovementOrChangeStock`; `insufficientExitLeavesDatabaseUnchanged` |
| CP-08 | RF-04 | Historial de referencia, PEPS. | Costo salida $100; valor final $400. | Automatizado: pasa. | `InventoryCostingServiceTest`; prueba JDBC |
| CP-09 | RF-04 | Mismo historial, promedio ponderado. | Costo salida $125; valor final $375. | Automatizado: pasa. | `InventoryCostingServiceTest`; prueba JDBC |
| CP-10 | RF-04, RF-05 | Recalcular ambos métodos sin editar movimientos. | Existencia física e historial iguales; valores recalculados. | Automatizado: 3 movimientos comunes; 15 unidades bajo ambos métodos. | `storesMovementsAndReadsThemBackThroughANewServiceInstance` |
| CP-11 | RF-01, RF-02 | Consultar o mover un producto inexistente. | Informar inexistencia; no crear movimientos. | Automatizado: pasa. | `nonexistentProductDoesNotCreateMovements` |
| CP-12 | RNF-02 | Registrar movimientos y crear otra instancia del servicio. | Datos continúan disponibles en la base. | Automatizado con H2 en memoria compartida. Reinicio real de aplicación contra Supabase pendiente. | `storesMovementsAndReadsThemBackThroughANewServiceInstance` |
| CP-13 | RNF-04 | Insertar un movimiento cuya fecha hace inválido el historial cronológico. | Fallo y rollback del movimiento insertado. | Automatizado: pasa; se verifica historial y stock previos. | `recalculationFailureAfterInsertRollsBackTheTransaction` |
| CP-14 | RNF-06 | Ejecutar regresión tras cambios. | Pruebas anteriores siguen aprobadas. | Automatizado: suite completa aprobada. | Salida de `mvn test` |
| CP-15 | RF-06 | Registrar dos entradas, una salida y otra entrada. | El kardex conserva los costos de compra, el costo promedio de salida y el saldo corrido correcto. | Automatizado en `InventoryCostingServiceTest`. | `movingAverageLedgerKeepsRunningPurchaseSaleAndBalanceValues` |
| CP-16 | RF-07 | Crear primer administrador y trabajador, incluyendo contraseñas cortas y vacías; comprobar credenciales, rol y baja. | Ambos roles aceptan contraseñas no vacías de cualquier longitud; rechazan contraseñas vacías; usuarios inválidos o desactivados no inician sesión; no se desactiva el último administrador. | Automatizado en `UserServiceTest`. | `createsFirstAdminAuthenticatesHashedPasswordAndManagesWorkerAccounts`; `acceptsAnyNonemptyPasswordAndProtectsTheLastActiveAdmin`; `acceptsShortPasswordForInitialAdminAndAuthenticatesIt` |
| CP-17 | RF-07 | Iniciar sesión como administrador y como trabajador en GUI y consola; intentar registrar productos, cambiar fechas y administrar usuarios como trabajador. | El administrador puede gestionar productos, usuarios y fechas; el trabajador solo consulta y registra movimientos con fecha UTC automática; ningún rol puede editar/eliminar movimientos existentes. | Verificación funcional pendiente. | Prueba manual GUI/consola |
| CP-18 | RF-08 | Cambiar nombre/contraseña con credenciales correctas e incorrectas; guardar modo noche y consultar preferencia. | Solo se aceptan credenciales actuales válidas; nombre y contraseña nuevos permiten iniciar sesión; modo noche permanece guardado por cuenta. | Automatizado para servicios; interfaz pendiente de verificación manual. | `updatesUsernamePasswordAndPersistsAppearancePreference` |
| CP-19 | RF-09 | Registrar 2 cajas de 12 piezas a $3.50 por pieza y retirar 1 caja. | Persistir 24 pzas de entrada, 12 pzas de salida y mantener 12 pzas; el costo por pieza no se modifica al convertir cantidad. | Automatizado: pasa en servicio; persistencia de equivalencia probada en repositorio. | `convertsPackageQuantitiesToPiecesForEntriesAndExits`; `persistsThePackagePieceEquivalence` |

## Evidencia y limitaciones

- Resultado automatizado: 11 pruebas, 0 fallos, 0 errores, 0 omitidas.
- Las pruebas automatizadas cubren servicios y persistencia JDBC/H2; los permisos de interfaz y consola requieren completar CP-17 manualmente.
- No se validó conexión real a Supabase, porque no se proporcionaron credenciales ni configuración de red.
- Se debe capturar la ejecución real de la aplicación y las consultas en Supabase para completar la evidencia gráfica del informe final.
